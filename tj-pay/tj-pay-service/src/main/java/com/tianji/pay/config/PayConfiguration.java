package com.tianji.pay.config;

import com.tianji.pay.third.ali.config.AliPayProperties;
import com.tianji.pay.third.wx.config.WxPayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({WxPayProperties.class, AliPayProperties.class})
public class PayConfiguration {
}
