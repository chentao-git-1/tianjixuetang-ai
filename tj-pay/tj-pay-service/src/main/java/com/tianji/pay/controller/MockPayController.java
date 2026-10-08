package com.tianji.pay.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.pay.domain.po.PayOrder;
import com.tianji.pay.mapper.PayOrderMapper;
import com.tianji.pay.sdk.constants.PayConstants;
import com.tianji.pay.sdk.dto.PayResultDTO;
import com.tianji.pay.service.PaymentSuccessService;
import com.tianji.pay.third.mock.LocalMockPayment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/mock-pay")
@LocalMockPayment
@RequiredArgsConstructor
@Tag(name = "本机模拟支付（仅local）")
public class MockPayController {
    private final PayOrderMapper payOrderMapper;
    private final PaymentSuccessService paymentSuccessService;

    @PostMapping("/{payOrderNo}/success")
    @Operation(summary = "模拟现有支付单支付成功，需要本机直连及开发令牌")
    public PayResultDTO success(@PathVariable("payOrderNo") Long payOrderNo,
                               @Parameter(description = "本机开发令牌，至少32个字符", required = true)
                               @RequestHeader("X-Mock-Pay-Token") String token) {
        PayOrder order = payOrderMapper.selectOne(Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getPayOrderNo, payOrderNo)
                .eq(PayOrder::getPayChannelCode, PayConstants.MOCK_CHANNEL_CODE));
        if (order == null) {
            throw new BadRequestException("模拟支付单不存在");
        }
        return paymentSuccessService.complete(payOrderNo, order.getAmount(), LocalDateTime.now(),
                PayConstants.MOCK_CHANNEL_CODE);
    }
}
