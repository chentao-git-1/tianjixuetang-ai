package com.tianji.pay.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.alipay.easysdk.factory.Factory;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.CommonException;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.JsonUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.pay.domain.po.RefundOrder;
import com.tianji.pay.sdk.constants.PayConstants;
import com.tianji.pay.sdk.constants.PayErrorInfo;
import com.tianji.pay.sdk.dto.RefundResultDTO;
import com.tianji.pay.service.INotifyService;
import com.tianji.pay.service.PaymentSuccessService;
import com.tianji.pay.service.IRefundOrderService;
import com.tianji.pay.third.ali.AliPayService;
import com.tianji.pay.third.model.RefundStatus;
import com.tianji.pay.third.wx.config.WxPayProperties;
import com.tianji.pay.third.ali.config.AliPayProperties;
import com.alipay.easysdk.kernel.Config;
import com.wechat.pay.contrib.apache.httpclient.auth.Verifier;
import com.wechat.pay.contrib.apache.httpclient.cert.CertificatesManager;
import com.wechat.pay.contrib.apache.httpclient.exception.NotFoundException;
import com.wechat.pay.contrib.apache.httpclient.exception.ParseException;
import com.wechat.pay.contrib.apache.httpclient.exception.ValidationException;
import com.wechat.pay.contrib.apache.httpclient.notification.Notification;
import com.wechat.pay.contrib.apache.httpclient.notification.NotificationHandler;
import com.wechat.pay.contrib.apache.httpclient.notification.NotificationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyServiceImpl implements INotifyService {
    private final ObjectProvider<CertificatesManager> certificatesManager;
    private final WxPayProperties properties;
    private final AliPayProperties aliProperties;
    private final ObjectProvider<Config> aliPayConfig;
    private final PaymentSuccessService paymentSuccessService;
    private final RabbitMqHelper rabbitMqHelper;
    private final IRefundOrderService refundOrderService;

    @Override
    public void handleWxPayNotify(NotificationRequest request) {
        // 1.尝试校验微信通知请求参数，安全校验
        Notification notification = checkWxNotifyRequest(request);
        if (notification == null || !StringUtils.equals(notification.getEventType(), "TRANSACTION.SUCCESS")) {
            // 通知类型不是支付成功，不做处理
            return;
        }
        // 2.解析回调加密的数据
        String decryptData = notification.getDecryptData();
        JSONObject data = JsonUtils.parseObj(decryptData);

        // 3.获取用于业务校验的基本信息
        // 3.1.交易单号
        Long tradingOrderNo = data.getLong("out_trade_no");
        // 3.2.订单金额
        JSONObject amountObject = data.getJSONObject("amount");
        Integer amount = amountObject == null ? null : amountObject.getInt("total");
        // 3.3.订单支付时间
        LocalDateTime successTime = data.getLocalDateTime("success_time", LocalDateTime.now());

        // 4.校验通知数据，主要是业务校验、幂等校验
        paymentSuccessService.complete(tradingOrderNo, amount, successTime, PayConstants.WX_CHANNEL_CODE);
    }


    @Override
    public void handleWxPayRefundNotify(NotificationRequest request) {
        // 1.尝试校验微信通知请求参数，安全校验
        Notification notification = checkWxNotifyRequest(request);
        if (notification == null || !StringUtils.equalsAny(
                notification.getEventType(), "REFUND.SUCCESS", "REFUND.ABNORMAL", "REFUND.CLOSED")) {
            // 通知类型错误，直接返回
            log.debug("微信退款通知类型异常");
            return;
        }

        // 2.解析通知数据
        String decryptData = notification.getDecryptData();
        JSONObject data = JsonUtils.parseObj(decryptData);
        // 2.1.退款单号
        Long refundOrderNo = data.getLong("out_refund_no");
        if (refundOrderNo == null) {
            log.error("微信通知数据有误，缺少退款单号");
            throw new BadRequestException("微信通知数据有误，缺少退款单号");
        }
        // 2.2.退款状态
        String statusStr = notification.getEventType();
        RefundStatus status = handleWxRefundStatus(statusStr);

        // 3.幂等性校验
        RefundOrder refundOrder = checkRefundData(refundOrderNo, status, null);
        if (refundOrder == null) return;

        // 4.发送MQ通知业务端
        rabbitMqHelper.send(
                MqConstants.Exchange.PAY_EXCHANGE,
                MqConstants.Key.REFUND_CHANGE,
                RefundResultDTO.builder()
                        .status(status == RefundStatus.SUCCESS ? RefundResultDTO.SUCCESS : RefundResultDTO.FAILED)
                        .bizPayOrderId(refundOrder.getBizOrderNo())
                        .bizRefundOrderId(refundOrder.getBizRefundOrderNo())
                        .refundChannel(refundOrder.getRefundChannel())
                        .refundOrderNo(refundOrder.getRefundOrderNo())
                        .msg(data.getStr("msg"))
                        .build()
        );
    }

    @Override
    public void handleAliPayNotify(Map<String, String> request) {
        if (!aliProperties.isEnabled() || aliPayConfig.getIfAvailable() == null) {
            throw new BadRequestException("支付宝支付已禁用");
        }
        log.error("收到阿里支付通知信息，request = {}", request);
        // 1.判断是否是成功通知
        String tradeStatus = request.get("trade_status");
        if (!StrUtil.equals(tradeStatus, "TRADE_SUCCESS")) {
            // 通知结果不是成功，直接结束
            return;
        }
        // 2.验签
        checkAliNotifyRequest(request);

        // 3.获取用于业务校验的基本信息
        // 3.1.交易单号
        String out_trade_no = request.get("out_trade_no");
        Long tradingOrderNo = StringUtils.isNumeric(out_trade_no) ? Long.valueOf(out_trade_no) : null;
        // 3.2.订单金额，阿里返回的订单金额要乘100
        String total_amount = request.get("total_amount");
        Integer amount = StringUtils.isNotBlank(total_amount) ? AliPayService.transferStringAmount2Int(total_amount) : null;
        // 3.3.订单支付时间
        String success_time = request.get("notify_time");
        LocalDateTime successTime = StringUtils.isBlank(success_time) ?
                LocalDateTime.now() : DateUtils.parse(success_time, DateUtils.DEFAULT_DATE_TIME_FORMAT);

        // 4.校验通知数据，主要是业务校验、幂等校验
        paymentSuccessService.complete(tradingOrderNo, amount, successTime, PayConstants.ALI_CHANNEL_CODE);
    }


    private RefundOrder checkRefundData(Long refundOrderNo, RefundStatus status, String channel) {
        // 1.查询退款单
        RefundOrder refundOrder = refundOrderService.queryByRefundOrderNo(refundOrderNo);
        // 2.判断是否为空
        if (refundOrder == null) {
            throw new BadRequestException("通知数据有误，退款单不存在");
        }
        // 3.判断状态是否变更
        if (status.equalsValue(refundOrder.getStatus())) {
            // 订单状态没有变化，属于重复通知
            return null;
        }
        // 4.更新退款单状态
        boolean success = refundOrderService.lambdaUpdate()
                .set(RefundOrder::getStatus, status.getValue())
                .set(StringUtils.isNotBlank(channel), RefundOrder::getRefundChannel, channel)
                .eq(RefundOrder::getId, refundOrder.getId())
                .eq(RefundOrder::getStatus, refundOrder.getStatus())
                .update();
        if(!success){
            return null;
        }
        return refundOrder;
    }

    private RefundStatus handleWxRefundStatus(String statusStr) {
        if (StringUtils.equalsAny(statusStr, "REFUND.CLOSED", "REFUND.ABNORMAL")) {
            return RefundStatus.FAILED;
        }
        if ("REFUND.SUCCESS".equals(statusStr)) {
            return RefundStatus.SUCCESS;
        }
        return RefundStatus.UN_KNOWN;
    }


    private void checkAliNotifyRequest(Map<String, String> request) {
        try {
            Boolean isValid = Factory.Payment.Common().verifyNotify(request);
            if (!isValid) {
                // 通知签名有误
                log.error("阿里支付通知回调验签失败，request = {}", request);
                throw new BadRequestException(PayErrorInfo.INVALID_NOTIFY_PARAM);
            }
        } catch (Exception e) {
            log.error("获取阿里验签工具异常", e);
            throw new CommonException("获取阿里验签工具异常", e);
        }
    }


    @Nullable
    private Notification checkWxNotifyRequest(NotificationRequest request) {
        CertificatesManager manager = certificatesManager.getIfAvailable();
        if (!properties.isEnabled() || manager == null) {
            throw new BadRequestException("微信支付已禁用");
        }
        try {
            Verifier verifier = manager.getVerifier(properties.getMchId());
            String apiV3Key = properties.getApiV3Key();
            NotificationHandler handler = new NotificationHandler(verifier, apiV3Key.getBytes(StandardCharsets.UTF_8));
            // 验签和解析请求体
            return handler.parse(request);

        } catch (NotFoundException e) {
            log.error("找不到商户{}的校验证书", properties.getMchId(), e);
            throw new BadRequestException("找不到商户校验证书");
        } catch (ValidationException e) {
            log.error("微信回调结果校验失败", e);
            throw new BadRequestException(400, "微信回调结果校验失败", e);
        } catch (ParseException e) {
            log.error("微信回调结果解析失败", e);
            throw new BadRequestException(400, "微信回调结果解析失败", e);
        } catch (RuntimeException e) {
            log.error("微信回调结果处理失败", e);
            throw new BadRequestException(400, "微信回调结果处理失败", e);
        }
    }

}
