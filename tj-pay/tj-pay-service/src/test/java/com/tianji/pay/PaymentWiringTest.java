package com.tianji.pay;

import com.alipay.easysdk.kernel.Config;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.pay.config.PayConfiguration;
import com.tianji.pay.controller.*;
import com.tianji.pay.mapper.*;
import com.tianji.pay.service.PaymentSuccessService;
import com.tianji.pay.service.impl.*;
import com.tianji.pay.third.CommonPayProperties;
import com.tianji.pay.third.ali.AliPayService;
import com.tianji.pay.third.ali.config.*;
import com.tianji.pay.third.mock.*;
import com.tianji.pay.third.wx.*;
import com.tianji.pay.third.wx.config.*;
import com.wechat.pay.contrib.apache.httpclient.cert.CertificatesManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentWiringTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Dependencies.class, PayConfiguration.class,
                    WxConfiguration.class, WxPayClient.class, WxPayService.class,
                    AliPayConfiguration.class, AliPayService.class,
                    MockPayConfiguration.class, MockPayService.class, MockPayController.class,
                    PaymentSuccessService.class, PayOrderServiceImpl.class, RefundOrderServiceImpl.class,
                    NotifyServiceImpl.class, NotifyController.class, PayChannelServiceImpl.class, PayChannelController.class);

    @Test
    void allRealChannelsDisabledStartWithoutSdkOrCredentialBeans() {
        runner.withPropertyValues("spring.profiles.active=local", "tj.pay.wx.enabled=false",
                "tj.pay.ali.enabled=false", "tj.pay.mock.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(WxPayProperties.class)
                    .hasSingleBean(AliPayProperties.class).hasSingleBean(NotifyController.class)
                    .hasSingleBean(PayOrderServiceImpl.class).hasSingleBean(RefundOrderServiceImpl.class)
                    .doesNotHaveBean(CertificatesManager.class).doesNotHaveBean(CloseableHttpClient.class)
                    .doesNotHaveBean(Config.class).doesNotHaveBean(WxPayClient.class)
                    .doesNotHaveBean(WxPayService.class).doesNotHaveBean(AliPayService.class)
                    .doesNotHaveBean(MockPayController.class);
            NotifyServiceImpl notify = context.getBean(NotifyServiceImpl.class);
            assertThatThrownBy(() -> notify.handleWxPayNotify(null)).hasMessageContaining("微信支付已禁用");
            assertThatThrownBy(() -> notify.handleAliPayNotify(java.util.Map.of())).hasMessageContaining("支付宝支付已禁用");
            assertThat(context.getBean(PayChannelController.class).listAllPayChannels()).isEmpty();
        });
    }

    @Test
    void localMockRegistersChannelWithoutRealSdk() {
        runner.withPropertyValues("spring.profiles.active=local", "tj.pay.mock.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(MockPayController.class)
                    .hasSingleBean(MockPayService.class).hasSingleBean(MockPayProperties.class)
                    .doesNotHaveBean(CertificatesManager.class).doesNotHaveBean(Config.class);
            assertThat(context.getBean(PayChannelController.class).listAllPayChannels())
                    .extracting("channelCode").containsExactly("mockPay");
        });
    }

    @Test
    void mockNeverRegistersInOtherOrMixedProfilesEvenWhenEnabled() {
        for (String profile : new String[]{"dev", "test", "prod", "local,dev", "local,staging"}) {
            runner.withPropertyValues("spring.profiles.active=" + profile, "tj.pay.mock.enabled=true")
                    .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(MockPayController.class)
                            .doesNotHaveBean(MockPayService.class).doesNotHaveBean(MockPayConfiguration.class));
        }
    }

    @Test
    void conflictingRealAndMockFlagsFailBeforeAnySdkInitialization() {
        runner.withPropertyValues("spring.profiles.active=local", "tj.pay.mock.enabled=true", "tj.pay.wx.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("模拟支付与真实支付不能同时启用");
                });
    }

    @Test
    void enabledRealChannelsRejectMissingCredentialsBeforeNetworkAccess() {
        runner.withPropertyValues("tj.pay.wx.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("启用微信支付必须配置 tj.pay.wx.app-id");
        });
        runner.withPropertyValues("tj.pay.ali.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("启用支付宝必须配置 tj.pay.ali.app-id");
        });
    }

    @Test
    void lastLocalImportOverridesRemotePaymentFlags() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=classpath:mock-config-test.yml").run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MockPayService.class)
                            .doesNotHaveBean(CertificatesManager.class).doesNotHaveBean(Config.class);
                    assertThat(context.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
                    assertThat(context.getEnvironment().getProperty("tj.pay.wx.enabled")).isEqualTo("false");
                });
    }

    @Configuration
    static class Dependencies {
        @Bean PayOrderMapper payOrderMapper() { return mock(PayOrderMapper.class); }
        @Bean PayChannelMapper payChannelMapper() { return mock(PayChannelMapper.class); }
        @Bean RefundOrderMapper refundOrderMapper() { return mock(RefundOrderMapper.class); }
        @Bean RabbitMqHelper rabbitMqHelper() { return mock(RabbitMqHelper.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean CommonPayProperties commonPayProperties() { return new CommonPayProperties(); }
    }
}
