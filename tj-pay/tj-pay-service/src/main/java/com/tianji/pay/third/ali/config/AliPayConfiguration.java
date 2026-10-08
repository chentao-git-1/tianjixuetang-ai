package com.tianji.pay.third.ali.config;

import com.alipay.easysdk.factory.Factory;
import com.alipay.easysdk.kernel.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

@Configuration
@ConditionalOnProperty(prefix = "tj.pay.ali", name = "enabled", havingValue = "true")
@ConditionalOnExpression("!${tj.pay.mock.enabled:false}")
public class AliPayConfiguration {

    @Bean
    public Config aliPayConfig(AliPayProperties properties) {
        Assert.hasText(properties.getAppId(), "启用支付宝必须配置 tj.pay.ali.app-id");
        Assert.hasText(properties.getMerchantPrivateKey(), "启用支付宝必须配置 tj.pay.ali.merchant-private-key");
        Assert.hasText(properties.getPublicKey(), "启用支付宝必须配置 tj.pay.ali.public-key");
        Config config = new Config();
        config.protocol = properties.getProtocol();
        config.gatewayHost = properties.getGatewayHost();
        config.signType = properties.getSignType();
        config.appId = properties.getAppId();
        config.merchantPrivateKey = properties.getMerchantPrivateKey();
        config.alipayPublicKey = properties.getPublicKey();
        Factory.setOptions(config);
        return config;
    }
}
