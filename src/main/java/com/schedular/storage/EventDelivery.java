package com.schedular.storage;

/**
 * event_deliveries 表领域 record；status：PENDING → DELIVERED / EXHAUSTED。
 * 业务定位键为 UNIQUE(event_id, subscription_id)，自增 id 不进 record。
 */
public record EventDelivery(
        String eventId,
        Integer subscriptionId,
        String status,
        int attempts,
        String nextRetryAt,
        String deliveredAt,
        Integer lastStatusCode,
        String lastError,
        String createdAt) {
}
