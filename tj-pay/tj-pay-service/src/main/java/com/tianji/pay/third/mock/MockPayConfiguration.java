package com.tianji.pay.third.mock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.util.Assert;

@Configuration
@LocalMockPayment
@EnableConfigurationProperties(MockPayProperties.class)
public class MockPayConfiguration {
    @Bean
    public FilterRegistrationBean<MockPayAccessFilter> mockPayAccessFilter(MockPayProperties properties,
                                                                          Environment environment) {
        Assert.state(!environment.getProperty("tj.pay.wx.enabled", Boolean.class, false)
                        && !environment.getProperty("tj.pay.ali.enabled", Boolean.class, false),
                "模拟支付与真实支付不能同时启用");
        FilterRegistrationBean<MockPayAccessFilter> registration =
                new FilterRegistrationBean<>(new MockPayAccessFilter(properties));
        registration.addUrlPatterns("/mock-pay/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
