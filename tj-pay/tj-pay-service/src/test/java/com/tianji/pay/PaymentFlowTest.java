package com.tianji.pay;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.pay.controller.MockPayController;
import com.tianji.pay.domain.po.PayOrder;
import com.tianji.pay.mapper.PayOrderMapper;
import com.tianji.pay.sdk.constants.PayConstants;
import com.tianji.pay.sdk.dto.PayApplyDTO;
import com.tianji.pay.sdk.dto.PayResultDTO;
import com.tianji.pay.service.PaymentSuccessService;
import com.tianji.pay.service.IPayOrderService;
import com.tianji.pay.service.impl.PayOrderServiceImpl;
import com.tianji.pay.third.mock.MockPayService;
import com.tianji.pay.third.model.PayStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(PaymentFlowTest.Config.class)
@ActiveProfiles("local")
@TestPropertySource(properties = "tj.pay.mock.enabled=true")
class PaymentFlowTest {
    @Autowired IPayOrderService orders;
    @Autowired PaymentSuccessService success;
    @Autowired MockPayController controller;
    @Autowired MockPayService mockPay;
    @Autowired RabbitMqHelper mq;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        jdbc.execute("DELETE FROM pay_order");
        reset(mq);
    }

    @Test
    void createsRealLocalOrderAndCompletesOnlyOnceWithCompatibleMessage() {
        String url = orders.applyPayOrder(apply());
        PayOrder order = orders.queryByBizOrderNo(99L);
        assertThat(url).isEqualTo("mockpay://pay-orders/" + order.getPayOrderNo());
        assertThat(order.getAmount()).isEqualTo(12800);
        assertThat(order.waitBuyerPay()).isTrue();
        assertThat(orders.applyPayOrder(apply())).isEqualTo(url);

        PayResultDTO result = controller.success(order.getPayOrderNo(), "checked-by-filter");
        assertThat(result.getStatus()).isEqualTo(PayResultDTO.SUCCESS);
        assertThat(result.getBizOrderId()).isEqualTo(99L);
        assertThat(result.getPayChannel()).isEqualTo(PayConstants.MOCK_CHANNEL_CODE);
        assertThat(orders.queryByBizOrderNo(99L).success()).isTrue();
        assertThat(orders.queryPayResult(99L)).usingRecursiveComparison().ignoringFields("msg").isEqualTo(result);
        assertThat(controller.success(order.getPayOrderNo(), "checked-by-filter")).isEqualTo(result);
        orders.checkPayOrder(order); // 使用模拟确认前的旧快照，不能重复通知或覆盖成功状态。
        verify(mq, times(1)).send(MqConstants.Exchange.PAY_EXCHANGE, MqConstants.Key.PAY_SUCCESS, result);
        assertThatThrownBy(() -> orders.applyPayOrder(apply())).isInstanceOf(BizIllegalException.class);
    }

    @Test
    void rejectsMissingMismatchedClosedAndExpiredOrders() {
        assertThatThrownBy(() -> controller.success(1L, "checked" )).isInstanceOf(BadRequestException.class);
        orders.applyPayOrder(apply());
        PayOrder order = orders.queryByBizOrderNo(99L);
        assertThatThrownBy(() -> success.complete(order.getPayOrderNo(), 1, LocalDateTime.now(), "mockPay"))
                .isInstanceOf(BizIllegalException.class);
        assertThatThrownBy(() -> success.complete(order.getPayOrderNo(), 12800, LocalDateTime.now(), "wxPay"))
                .isInstanceOf(BizIllegalException.class);
        jdbc.update("UPDATE pay_order SET pay_over_time=?", LocalDateTime.now().minusMinutes(1));
        assertThatThrownBy(() -> controller.success(order.getPayOrderNo(), "checked"))
                .isInstanceOf(BizIllegalException.class);
        jdbc.update("UPDATE pay_order SET status=?", PayStatus.TRADE_CLOSED.getValue());
        assertThatThrownBy(() -> controller.success(order.getPayOrderNo(), "checked"))
                .isInstanceOf(BizIllegalException.class);
        verifyNoInteractions(mq);
    }

    @Test
    void rollsBackOnSynchronousMqFailureAndAllowsRetry() {
        orders.applyPayOrder(apply());
        PayOrder order = orders.queryByBizOrderNo(99L);
        doThrow(new IllegalStateException("broker unavailable")).when(mq).send(anyString(), anyString(), any());
        assertThatThrownBy(() -> controller.success(order.getPayOrderNo(), "checked"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(orders.queryByBizOrderNo(99L).waitBuyerPay()).isTrue();
        reset(mq);
        controller.success(order.getPayOrderNo(), "checked");
        assertThat(orders.queryByBizOrderNo(99L).success()).isTrue();
        verify(mq, times(1)).send(eq("pay.topic"), eq("pay.success"), any(PayResultDTO.class));
    }

    @Test
    void concurrentCompletionsSendAtMostOneMessage() throws Exception {
        orders.applyPayOrder(apply());
        Long number = orders.queryByBizOrderNo(99L).getPayOrderNo();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> action = () -> {
            start.await();
            try {
                controller.success(number, "checked");
            } catch (BizIllegalException expected) {
                // CAS失败方可重新查询已成功的结果。
            }
            return null;
        };
        try {
            Future<Void> first = executor.submit(action);
            Future<Void> second = executor.submit(action);
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(orders.queryByBizOrderNo(99L).success()).isTrue();
        verify(mq, times(1)).send(eq("pay.topic"), eq("pay.success"), any(PayResultDTO.class));
    }

    @Test
    void mockRefundDoesNotInventSuccess() {
        assertThatThrownBy(() -> mockPay.refundOrder("1", "2", 100, 100))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(mq);
    }

    private PayApplyDTO apply() {
        return PayApplyDTO.builder().bizOrderNo(99L).bizUserId(7L).amount(12800)
                .payChannelCode("mockPay").payType(4).orderInfo("测试课程").build();
    }

    @Configuration
    @EnableTransactionManagement
    @MapperScan("com.tianji.pay.mapper")
    @ImportAutoConfiguration(MybatisPlusAutoConfiguration.class)
    @Import({PaymentSuccessService.class,
            PayOrderServiceImpl.class, MockPayService.class, MockPayController.class})
    static class Config {
        @Bean DataSource dataSource() {
            DataSource source = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                    .setType(EmbeddedDatabaseType.H2).build();
            new JdbcTemplate(source).execute("CREATE TABLE pay_order (id BIGINT PRIMARY KEY, biz_order_no BIGINT UNIQUE, "
                    + "pay_order_no BIGINT UNIQUE, biz_user_id BIGINT, pay_channel_code VARCHAR(30), amount INT, "
                    + "pay_type INT, status INT, expand_json VARCHAR(255), notify_url VARCHAR(255), notify_times INT, "
                    + "notify_status INT, result_code VARCHAR(255), result_msg VARCHAR(255), pay_success_time TIMESTAMP(9), "
                    + "pay_over_time TIMESTAMP(9), qr_code_url VARCHAR(255), create_time TIMESTAMP, update_time TIMESTAMP, "
                    + "creater BIGINT, updater BIGINT, deleted TINYINT DEFAULT 0)");
            return source;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean RabbitMqHelper rabbitMqHelper() { return mock(RabbitMqHelper.class); }
    }
}
