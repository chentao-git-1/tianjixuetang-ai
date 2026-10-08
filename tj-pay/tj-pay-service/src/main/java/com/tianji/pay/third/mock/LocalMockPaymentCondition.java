package com.tianji.pay.third.mock;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** 仅单独启用local；local与其他环境混用时也不会注册模拟入口。 */
public class LocalMockPaymentCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String[] profiles = context.getEnvironment().getActiveProfiles();
        return profiles.length == 1 && "local".equals(profiles[0]);
    }
}
