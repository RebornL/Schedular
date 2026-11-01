package com.schedular.taskmanager;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.schedular.storage.Subscription;
import java.util.List;

/**
 * 订阅响应模型。secret 仅创建响应返回一次（列表/停用传 null，
 * 结合 jackson non_null 不出现在 JSON；docs/design/05-security.md）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubscriptionView(
        long subscriptionId,
        String callbackUrl,
        List<String> filterTaskIds,
        List<String> filterEventTypes,
        String minSeverity,
        Integer dedupWindowSeconds,
        boolean enabled,
        String secret,
        String createdAt) {

    public static SubscriptionView from(Subscription subscription, List<String> filterTaskIds,
                                        List<String> filterEventTypes) {
        return new SubscriptionView(subscription.id(), subscription.callbackUrl(),
                filterTaskIds, filterEventTypes, subscription.minSeverity(),
                subscription.dedupWindowSeconds(), subscription.enabled(), null,
                subscription.createdAt());
    }
}
