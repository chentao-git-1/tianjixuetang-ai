package com.tianji.pay.third.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnProperty(prefix = "tj.pay.mock", name = "enabled", havingValue = "true")
@Conditional(LocalMockPaymentCondition.class)
public @interface LocalMockPayment {
}
