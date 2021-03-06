package com.licc.trade.service;

import com.licc.btc.chbtcapi.ChbtcApi;
import java.util.Date;
import java.util.List;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import com.google.common.collect.Lists;
import com.licc.btc.chbtcapi.enums.EDeleteFlag;
import com.licc.btc.chbtcapi.enums.ETradeCurrency;
import com.licc.btc.chbtcapi.enums.ETradeOrderStatus;
import com.licc.btc.chbtcapi.enums.ETradeOrderType;
import com.licc.btc.chbtcapi.enums.ETradeResStatus;
import com.licc.btc.chbtcapi.req.CancelOrderReq;
import com.licc.btc.chbtcapi.req.GetOrderReq;
import com.licc.btc.chbtcapi.req.OrderReq;
import com.licc.btc.chbtcapi.res.order.GetOrderRes;
import com.licc.btc.chbtcapi.res.order.OrderRes;
import com.licc.btc.chbtcapi.res.ticker.TickerApiRes;
import com.licc.trade.domain.OrderNumber;
import com.licc.trade.domain.ParamConfig;
import com.licc.trade.domain.TradeOrder;
import com.licc.trade.domain.User;
import com.licc.trade.repostiory.ParamConfigRepostiory;
import com.licc.trade.repostiory.TradeOrderRepostiory;
import com.licc.trade.util.TradeUtil;

/**
 * 自动买卖订单主逻辑
 *
 * @author lichangchao
 * @version 1.0.0
 * @date 2017/5/22 15:06
 * @see
 */
@Service
@Transactional(isolation = Isolation.SERIALIZABLE)
public class TradeService {
    Logger                logger = LoggerFactory.getLogger(this.getClass());
    @Resource
    ParamConfigRepostiory configRepostiory;
    @Resource
    TradeOrderRepostiory  tradeOrderRepostiory;


    @Resource
    OrderNumberService    orderNumberService;
    @Resource
    TradeOrderService     tradeOrderService;

    /**
     * @param tradeCurrency 币种类型
     * @param user 用户
     */

    public void execute(ETradeCurrency tradeCurrency, User user) {
        // 根据币种和用户查询配置信息
        ParamConfig config = configRepostiory.findOneByUserIdAndCurrencyAndDeleteFlag(user.getId(), tradeCurrency.getValue(),
                EDeleteFlag.NORMAL.getIntegerCode());
        if (config == null) {
            logger.info("用户：" + user.getUserName() + " 币种：" + tradeCurrency.getValue() + "当前配置为空 请检查配置 ");
            return;
        }
        // 查询当前行情数据
        TickerApiRes tickerApiRes = ChbtcApi.ticker(tradeCurrency);
        if (tickerApiRes == null)
            return;

        // 更新当前订单状态
        updateOrderStatus(tradeCurrency, user);
        // 买入委托订单
        buyOrder(tradeCurrency, user, config, tickerApiRes);
        // 卖出委托订单
        sellOrder(tradeCurrency, user, config, tickerApiRes);
        // 取消超时订单
        cancelOverTimeOrder(tradeCurrency, user, config);
    }

    // 取消超时买入订单
    void cancelOverTimeOrder(ETradeCurrency tradeCurrency, User user, ParamConfig config) {
        List<Integer> buyStatusList = Lists.newArrayList(ETradeOrderStatus.WAIT.getKey(), ETradeOrderStatus.WAIT_NO.getKey());
        List<TradeOrder> tradeOrderList = tradeOrderRepostiory.findByUserIdAndCurrencyAndBuyStatusIn(user.getId(), tradeCurrency.getValue(),
                buyStatusList);
        if (CollectionUtils.isEmpty(tradeOrderList))
            return;
        tradeOrderList.forEach(tradeOrder -> {
            if (ETradeOrderStatus.SUCCESS.getKey() != tradeOrder.getBuyStatus()
                    && System.currentTimeMillis() - tradeOrder.getCreateTime().getTime() >= config.getBuyOverTime()) {
                logger.info("状态(" + tradeOrder.getBuyStatus() + ")买单间隔时间:"
                        + (System.currentTimeMillis() - tradeOrder.getCreateTime().getTime() + "大于" + config.getBuyOverTime()));
                CancelOrderReq cancelOrderReq = new CancelOrderReq();
                cancelOrderReq.setId(tradeOrder.getBuyOrderId());
                cancelOrderReq.setTradeCurrency(tradeCurrency);
                cancelOrderReq.setAccessKey(user.getAccessKey());
                cancelOrderReq.setSecretKey(user.getSecretKey());
                ChbtcApi.cancelOrder(cancelOrderReq);
                tradeOrder.setBuyStatus(ETradeOrderStatus.CANCEL.getKey());
                tradeOrder.setSellStatus(ETradeOrderStatus.CANCEL.getKey());
                tradeOrderService.save(tradeOrder);
            }

        });

    }

    void updateOrderStatus(ETradeCurrency tradeCurrency, User user) {

        List<Integer> buyStatus = Lists.newArrayList(ETradeOrderStatus.SUCCESS.getKey(), ETradeOrderStatus.WAIT.getKey(),
                ETradeOrderStatus.WAIT_NO.getKey());
        List<Integer> sellStatus = Lists.newArrayList(ETradeOrderStatus.WAIT.getKey(), ETradeOrderStatus.BUY_SUCCESS_NO_SELL.getKey(),
                ETradeOrderStatus.WAIT_NO.getKey());
        List<TradeOrder> tradeOrders = tradeOrderService.findByUserIdAndCurrencyAndBuyStatusInOrUserIdAndCurrencyAndSellStatusIn(
                user.getId(), tradeCurrency.getValue(), buyStatus, user.getId(), tradeCurrency.getValue(), sellStatus);
        if (CollectionUtils.isEmpty(tradeOrders)) {
            logger.info("用户：" + user.getUserName() + " 币种：" + tradeCurrency.getValue() + "更新订单状态 》》未完成或者未取消的订单为空 ");
            return;
        }
        tradeOrders.forEach(tradeOrder -> {
            GetOrderReq getOrderReq = new GetOrderReq();
            getOrderReq.setTradeCurrency(tradeCurrency);
            getOrderReq.setAccessKey(user.getAccessKey());
            getOrderReq.setSecretKey(user.getSecretKey());
            // 修改委托买单的状态\
            if (tradeOrder.getBuyOrderId() != null) {
                getOrderReq.setId(tradeOrder.getBuyOrderId());
                if (ETradeOrderStatus.SUCCESS.getKey() != tradeOrder.getBuyStatus().intValue()
                        && ETradeOrderStatus.CANCEL.getKey() != tradeOrder.getBuyStatus().intValue()) {
                    GetOrderRes orderRes = ChbtcApi.getOrder(getOrderReq);
                    if (orderRes != null) {
                        tradeOrder.setBuyStatus(orderRes.getStatus());
                        tradeOrderService.save(tradeOrder);
                    }
                }
            }
            // 修改委托卖单的状态
            if (tradeOrder.getSellOrderId() != null) {
                if (ETradeOrderStatus.SUCCESS.getKey() != tradeOrder.getSellStatus().intValue()
                        && ETradeOrderStatus.CANCEL.getKey() != tradeOrder.getSellStatus().intValue()) {
                    getOrderReq.setId(tradeOrder.getSellOrderId());
                    GetOrderRes orderRes = ChbtcApi.getOrder(getOrderReq);
                    if (orderRes != null) {
                        tradeOrder.setSellStatus(orderRes.getStatus());
                    }
                    tradeOrderService.save(tradeOrder);
                }
            }
        });
    }

    /**
     * 委托卖单
     *
     * @param tradeCurrency
     * @param user
     * @param config
     * @param tickerApiRes
     */
    public void sellOrder(ETradeCurrency tradeCurrency, User user, ParamConfig config, TickerApiRes tickerApiRes) {

        // 查询委托买入成功 但是未卖出的订单
        List<TradeOrder> tradeOrders = tradeOrderRepostiory.findByUserIdAndCurrencyAndBuyStatusAndSellStatus(user.getId(),
                tradeCurrency.getValue(), ETradeOrderStatus.SUCCESS.getKey(), ETradeOrderStatus.BUY_SUCCESS_NO_SELL.getKey());

        if (CollectionUtils.isEmpty(tradeOrders)) {
            logger.info("用户：" + user.getUserName() + " 币种：" + tradeCurrency.getValue() + "委托卖单:买单完成且没有卖单的订单为空 ");
            return;
        }

        tradeOrders.forEach(tradeOrder -> {

            // 当前卖出价格-随机数
            String  sellPrice = config.getOrderSellPrice();
            if(StringUtils.isEmpty(sellPrice)) {
                sellPrice = TradeUtil
                    .getSellPriceByBuy(tradeOrder.getBuyPrice(), tradeOrder.getSubtractPrice());
            }else{
                 if(TradeUtil.diffString(tradeOrder.getBuyPrice(),sellPrice,"0")){
                     sellPrice = TradeUtil
                         .getSellPriceByBuy(tradeOrder.getBuyPrice(), tradeOrder.getSubtractPrice());
                 }
            }
            OrderReq orderReq = new OrderReq();
            orderReq.setPrice(sellPrice);
            orderReq.setAmount(tradeOrder.getBuyNumber());
            orderReq.setTradeCurrency(tradeCurrency);
            orderReq.setTradeOrderType(ETradeOrderType.ORDER_SELL);
            orderReq.setAccessKey(user.getAccessKey());
            orderReq.setSecretKey(user.getSecretKey());
            OrderRes orderRes = ChbtcApi.order(orderReq);
            if (ETradeResStatus.SUCCESS.getKey().equals(orderRes.getCode())) {// 卖出委托成功
                tradeOrder.setSellOrderId(orderRes.getId());
                tradeOrder.setSellStatus(ETradeOrderStatus.WAIT.getKey());
                tradeOrder.setSellPrice(sellPrice);
                tradeOrder.setBuyFees("0");
                tradeOrderService.save(tradeOrder);
            }

        });

    }

    /**
     * 委托买单
     *
     * @param tradeCurrency
     * @param user
     * @param config
     * @param tickerApiRes
     */
    public void buyOrder(ETradeCurrency tradeCurrency, User user, ParamConfig config, TickerApiRes tickerApiRes) {
        // 根据币种和用户查询待成交的委托订单数量
        List<Integer> buyStatus = Lists.newArrayList(ETradeOrderStatus.SUCCESS.getKey(), ETradeOrderStatus.WAIT.getKey(),
                ETradeOrderStatus.WAIT_NO.getKey());
        List<Integer> sellStatus = Lists.newArrayList(ETradeOrderStatus.SUCCESS.getKey(), ETradeOrderStatus.CANCEL.getKey());
        Long sellNum = tradeOrderRepostiory.countByUserIdAndCurrencyAndBuyStatusInAndSellStatusNotIn(user.getId(), tradeCurrency.getValue(),
                buyStatus, sellStatus);
        // 如果待完成的委托卖出订单数量等于设置的最大委托笔数则不进行买卖交易
        if (sellNum == config.getMaxBuyNumber()) {
            logger.info("用户：" + user.getUserName() + " 币种：" + tradeCurrency.getValue() + "达到了最大卖出委托笔数");
            return;
        }

        // 判断最高值和当前买一价是不是小于设定的值 如果是则不进行买卖交易
        String high = tickerApiRes.getTicker().getHigh();
        String buy = tickerApiRes.getTicker().getBuy();
        Boolean flag = TradeUtil.diffString(high, buy, config.getHightBuyDiff());
        if (!flag) {
            logger.info("用户：" + user.getUserName() + " 币种：" + tradeCurrency.getValue() + "最高价(" + high + ")与买入价(" + buy + ")差值需要大于"
                    + config.getHightBuyDiff());
            return;
        }
        // 判断当前价格与上一个委托价格差是不是小于设定值 如果是则不进行买卖交易

        String lastPrice = tradeOrderService.getLastPriceByUserIdAndCurrency(user.getId(), tradeCurrency.getValue());
        if (!StringUtils.isEmpty(lastPrice)) {
            if (config.getDownBuyEnable()) {// 开关
                flag = TradeUtil.diffString(lastPrice, tickerApiRes.getTicker().getBuy(), config.getDownBuy());
                if (!flag) {
                    logger.info("用户：" + user.getUserName() + " 币种：" + tradeCurrency.getValue() + "最后委托价格(" + lastPrice + ")与买入价("
                            + tickerApiRes.getTicker().getBuy() + ")差值需要大于" + config.getDownBuy());
                    return;
                }
            }
        }

        // 判断卖一价和买一价差值
        String ticker_sell = tickerApiRes.getTicker().getSell();
        String ticker_buy = tickerApiRes.getTicker().getBuy();
        flag = TradeUtil.diffString(ticker_sell, ticker_buy, config.getSellBuyDiff());
        if (!flag) {
            logger.info("用户：" + user.getUserName() + " 币种：" + tradeCurrency.getValue() + "卖一价(" + ticker_sell + ")和买一价(" + ticker_buy
                    + ")差值需要大于" + config.getSellBuyDiff());
            return;
        }
        // 获取买单数量
        List<OrderNumber> orderNumbers = orderNumberService.listByUserIdAndCurrency(user.getId(), tradeCurrency.getValue());
        OrderNumber orderNumber = TradeUtil.getBuyNumber(tickerApiRes.getTicker().getHigh(), ticker_buy, orderNumbers);
        // 委托买单
        String buyPrice = TradeUtil.getBuyPrice(tickerApiRes.getTicker().getBuy());
        String subtractPrice = orderNumber.getSubtractPrice();
        Integer buyNumber = orderNumber.getOrderNumber();
        logger.info("用户：" + user.getUserName() + " 币种：" + tradeCurrency.getValue() + "买单数量：" + buyNumber);
        if (buyNumber > 0) {
            OrderReq orderReq = new OrderReq();
            orderReq.setPrice(buyPrice);
            orderReq.setAmount(String.valueOf(buyNumber));
            orderReq.setTradeCurrency(tradeCurrency);
            orderReq.setTradeOrderType(ETradeOrderType.ORDER_BUY);
            orderReq.setAccessKey(user.getAccessKey());
            orderReq.setSecretKey(user.getSecretKey());
            OrderRes orderRes = ChbtcApi.order(orderReq);
            if (ETradeResStatus.SUCCESS.getKey().equals(orderRes.getCode())) {// 卖出委托成功
                TradeOrder tradeOrder = new TradeOrder();
                tradeOrder.setBuyNumber(String.valueOf(buyNumber));
                tradeOrder.setBuyPrice(buyPrice);
                tradeOrder.setBuyOrderId(orderRes.getId());
                tradeOrder.setBuyStatus(ETradeOrderStatus.WAIT.getKey());
                tradeOrder.setUserId(user.getId());
                tradeOrder.setCreateTime(new Date());
                tradeOrder.setBuyFees("0");
                tradeOrder.setSubtractPrice(subtractPrice);
                tradeOrder.setCurrency(tradeCurrency.getValue());
                tradeOrder.setSellStatus(ETradeOrderStatus.BUY_SUCCESS_NO_SELL.getKey());
                tradeOrderService.save(tradeOrder);
            } else {
                logger.info("用户：" + user.getUserName() + " 币种：" + tradeCurrency.getValue() + "数量：" + buyNumber + orderRes.getMessage());
            }
        }
    }
}
