package com.tianji.pay.third.mock;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "tj.pay.mock")
public class MockPayProperties {
    private boolean enabled;
    @ToString.Exclude
    private String token = "";
}
