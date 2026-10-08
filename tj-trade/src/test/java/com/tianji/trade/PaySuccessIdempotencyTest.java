package com.tianji.trade;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.promotion.PromotionClient;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.pay.sdk.client.PayClient;
import com.tianji.pay.sdk.dto.PayResultDTO;
import com.tianji.trade.config.TradeProperties;
import com.tianji.trade.constants.OrderStatus;
import com.tianji.trade.domain.dto.OrderDelayQueryDTO;
import com.tianji.trade.domain.po.Order;
import com.tianji.trade.mapper.OrderMapper;
import com.tianji.trade.service.ICartService;
import com.tianji.trade.service.IOrderDetailService;
import com.tianji.trade.service.IOrderService;
import com.tianji.trade.service.impl.OrderServiceImpl;
import com.tianji.trade.service.impl.PayServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaySuccessIdempotencyTest {
    @Test
    void duplicateMqDeliveryUpdatesDetailsAndNotifiesEnrollmentOnlyOnce() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Order.class);
        OrderMapper mapper = mock(OrderMapper.class);
        IOrderDetailService details = mock(IOrderDetailService.class);
        RabbitMqHelper mq = mock(RabbitMqHelper.class);
        OrderServiceImpl service = new OrderServiceImpl(mock(CourseClient.class), details, mock(ICartService.class),
                new TradeProperties(), mq, mock(PromotionClient.class));
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        Order order = new Order();
        order.setId(99L);
        order.setUserId(7L);
        order.setStatus(OrderStatus.NO_PAY.getValue());
        when(mapper.selectById(99L)).thenReturn(order);
        when(mapper.update(any(Order.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Wrapper<Order> wrapper = invocation.getArgument(1);
            assertThat(wrapper.getSqlSegment()).contains("status =");
            assertThat(((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper)
                    .getParamNameValuePairs().values()).contains(OrderStatus.NO_PAY.getValue());
            if (order.getStatus().equals(OrderStatus.NO_PAY.getValue())) {
                order.setStatus(OrderStatus.PAYED.getValue());
                return 1;
            }
            return 0;
        });
        when(details.queryCourseIdsByOrderId(99L)).thenReturn(List.of(123L));
        PayResultDTO result = PayResultDTO.builder().status(PayResultDTO.SUCCESS).bizOrderId(99L)
                .payOrderNo(123456L).payChannel("mockPay").successTime(LocalDateTime.now()).build();
        service.handlePaySuccess(result);
        service.handlePaySuccess(result);
        verify(details, times(1)).markDetailSuccessByOrderId(99L, "mockPay", result.getSuccessTime());
        verify(mq, times(1)).send(eq("order.topic"), eq("order.pay"), any());
    }

    @Test
    void waitingPaymentDelayQueryDoesNotInvokeSuccessHandler() {
        PayClient client = mock(PayClient.class);
        IOrderService orders = mock(IOrderService.class);
        RabbitMqHelper mq = mock(RabbitMqHelper.class);
        PayServiceImpl service = new PayServiceImpl(client, orders, mock(IOrderDetailService.class),
                new TradeProperties(), mq);
        Order order = new Order();
        order.setStatus(OrderStatus.NO_PAY.getValue());
        when(orders.getById(99L)).thenReturn(order);
        when(client.queryPayResult(99L)).thenReturn(PayResultDTO.builder().status(PayResultDTO.PAYING).build());
        service.queryPayResult(OrderDelayQueryDTO.init(99L));
        verify(orders, never()).handlePaySuccess(any());
        verify(mq).sendDelayMessage(eq("trade.delay.topic"), eq("delay.order.query"), any(), any());
    }
}
