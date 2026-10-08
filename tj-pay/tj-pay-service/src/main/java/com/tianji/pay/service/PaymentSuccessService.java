package com.tianji.pay.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.autoconfigure.redisson.annotations.Lock;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.pay.constants.NotifyStatus;
import com.tianji.pay.domain.po.PayOrder;
import com.tianji.pay.mapper.PayOrderMapper;
import com.tianji.pay.sdk.constants.PayConstants;
import com.tianji.pay.sdk.dto.PayResultDTO;
import com.tianji.pay.third.model.PayStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 已通过渠道认证的支付结果的业务处理，真实回调、查询补偿和本地模拟共用。
 */
@Service
@RequiredArgsConstructor
public class PaymentSuccessService {
    private final PayOrderMapper payOrderMapper;
    private final RabbitMqHelper rabbitMqHelper;

    @Transactional
    @Lock(name = PayConstants.RedisKeyFormatter.PAY_NOTIFY)
    public PayResultDTO complete(Long tradingOrderNo, Integer amount, LocalDateTime successTime, String channel) {
        if (tradingOrderNo == null || amount == null || amount <= 0 || successTime == null) {
            throw new BadRequestException("无效的支付成功参数");
        }
        PayOrder order = payOrderMapper.selectOne(Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getPayOrderNo, tradingOrderNo));
        if (order == null) {
            throw new BadRequestException("支付单不存在");
        }
        if (!channel.equals(order.getPayChannelCode()) || !amount.equals(order.getAmount())) {
            throw new BizIllegalException("支付渠道或金额与支付单不一致");
        }
        if (order.success()) {
            return result(order);
        }
        if (order.closed() || (!order.notCommit() && !order.waitBuyerPay())) {
            throw new BizIllegalException("支付单状态不允许支付");
        }
        if (PayConstants.MOCK_CHANNEL_CODE.equals(channel)
                && (!order.waitBuyerPay() || order.getPayOverTime() == null
                || !order.getPayOverTime().isAfter(LocalDateTime.now()))) {
            throw new BizIllegalException("模拟支付单未就绪或已超时");
        }
        // 条件更新是最终幂等屏障；同时防止切换渠道后旧回调写入新支付单。
        if (!markSuccess(order, successTime)) {
            throw new BizIllegalException("支付单状态已变更，请重新查询");
        }
        order.setStatus(PayStatus.TRADE_SUCCESS.getValue());
        order.setPaySuccessTime(successTime);
        PayResultDTO result = result(order);
        // 同步发送异常会回滚数据库事务，允许重试。跨数据库/MQ的原子性仍依赖现有架构。
        rabbitMqHelper.send(MqConstants.Exchange.PAY_EXCHANGE, MqConstants.Key.PAY_SUCCESS, result);
        return result;
    }

    public boolean markSuccess(PayOrder order, LocalDateTime successTime) {
        return payOrderMapper.update(null, Wrappers.<PayOrder>lambdaUpdate()
                .set(PayOrder::getStatus, PayStatus.TRADE_SUCCESS.getValue())
                .set(PayOrder::getNotifyStatus, NotifyStatus.CALLING.getValue())
                .set(PayOrder::getPaySuccessTime, successTime)
                .eq(PayOrder::getId, order.getId())
                .eq(PayOrder::getPayChannelCode, order.getPayChannelCode())
                .eq(PayOrder::getAmount, order.getAmount())
                .in(PayOrder::getStatus, PayStatus.NOT_COMMIT.getValue(), PayStatus.WAIT_BUYER_PAY.getValue())) == 1;
    }

    private PayResultDTO result(PayOrder order) {
        return PayResultDTO.builder().status(PayResultDTO.SUCCESS).msg(PayResultDTO.OK)
                .payOrderNo(order.getPayOrderNo()).bizOrderId(order.getBizOrderNo())
                .payChannel(order.getPayChannelCode()).successTime(order.getPaySuccessTime()).build();
    }
}
