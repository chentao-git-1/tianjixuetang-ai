package com.tianji.pay.third.mock;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.pay.domain.po.PayOrder;
import com.tianji.pay.mapper.PayOrderMapper;
import com.tianji.pay.sdk.constants.PayConstants;
import com.tianji.pay.third.IPayService;
import com.tianji.pay.third.model.PayStatusResponse;
import com.tianji.pay.third.model.PrepayResponse;
import com.tianji.pay.third.model.RefundResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service(PayConstants.MOCK_CHANNEL_CODE)
@LocalMockPayment
@RequiredArgsConstructor
public class MockPayService implements IPayService {
    private final PayOrderMapper payOrderMapper;

    @Override
    public PrepayResponse createPrepayOrder(String title, String orderNo, Integer amount) {
        if (amount == null || amount <= 0) {
            throw new BadRequestException("模拟支付金额必须为正数");
        }
        // 保持原支付API返回String的约定；二维码仅包含支付单号，不包含开发令牌。
        return PrepayResponse.builder().success(true).payUrl("mockpay://pay-orders/" + orderNo).build();
    }

    @Override
    public PayStatusResponse queryPayOrderStatus(String payOrderNo) {
        PayOrder order = payOrderMapper.selectOne(Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getPayOrderNo, Long.valueOf(payOrderNo))
                .eq(PayOrder::getPayChannelCode, PayConstants.MOCK_CHANNEL_CODE));
        if (order == null) {
            return PayStatusResponse.builder().success(false).msg("模拟支付单不存在").build();
        }
        return PayStatusResponse.builder().success(true).payOrderNo(payOrderNo).payStatus(order.getStatus())
                .totalAmount(order.getAmount()).successTime(order.getPaySuccessTime()).build();
    }

    @Override
    public RefundResponse refundOrder(String payOrderNo, String refundOrderNo, Integer refundAmount, Integer totalAmount) {
        throw new BadRequestException("本地模拟支付暂不支持退款");
    }

    @Override
    public RefundResponse queryRefundStatus(String orderNo, String refundOrderNo) {
        throw new BadRequestException("本地模拟支付暂不支持退款");
    }
}
