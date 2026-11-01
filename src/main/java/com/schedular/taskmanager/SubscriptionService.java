package com.schedular.taskmanager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schedular.common.ApiException;
import com.schedular.security.TenantContext;
import com.schedular.security.TenantPrincipal;
import com.schedular.security.TenantScope;
import com.schedular.storage.Subscription;
import com.schedular.storage.SubscriptionRepository;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 订阅 CRUD：创建（secret 一次性返回）、按租户列表、停用（enabled=0，无物理删除）。 */
@Service
public class SubscriptionService {

    private static final List<String> SEVERITIES = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SubscriptionRepository subscriptionRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                               ObjectMapper objectMapper, Clock clock) {
        this.subscriptionRepository = subscriptionRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public SubscriptionView create(SubscriptionRequest request) {
        TenantPrincipal principal = requireTenant();
        if (request.callbackUrl() == null || request.callbackUrl().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CALLBACK_URL",
                    "callback_url 必填");
        }
        validateCallbackUrl(request.callbackUrl());
        if (request.minSeverity() != null && !SEVERITIES.contains(request.minSeverity())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MIN_SEVERITY",
                    "min_severity 仅支持 LOW / MEDIUM / HIGH / CRITICAL");
        }
        Subscription subscription = new Subscription(
                null, principal.tenantId(), request.callbackUrl(), generateSecret(),
                toJson(request.filterTaskIds()), toJson(request.filterEventTypes()),
                request.minSeverity(), request.dedupWindowSeconds(), true, nowIso());
        long id = subscriptionRepository.insert(subscription);
        // secret 明文仅在本次响应返回一次
        return new SubscriptionView(id, subscription.callbackUrl(),
                request.filterTaskIds(), request.filterEventTypes(), subscription.minSeverity(),
                subscription.dedupWindowSeconds(), true, subscription.secret(),
                subscription.createdAt());
    }

    public List<SubscriptionView> list(String tenant) {
        TenantPrincipal principal = TenantContext.get();
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "缺少认证身份");
        }
        return subscriptionRepository.findByTenant(TenantScope.resolve(principal, tenant)).stream()
                .map(s -> SubscriptionView.from(s, fromJson(s.filterTaskIds()),
                        fromJson(s.filterEventTypes())))
                .toList();
    }

    public SubscriptionView disable(long id) {
        TenantPrincipal principal = requireTenant();
        Subscription existing = subscriptionRepository.findById(id)
                .filter(s -> s.tenantId().equals(principal.tenantId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND",
                        "订阅不存在: " + id));
        subscriptionRepository.disable(id);
        return new SubscriptionView(existing.id(), existing.callbackUrl(),
                fromJson(existing.filterTaskIds()), fromJson(existing.filterEventTypes()),
                existing.minSeverity(), existing.dedupWindowSeconds(), false, null,
                existing.createdAt());
    }

    private TenantPrincipal requireTenant() {
        TenantPrincipal principal = TenantContext.get();
        if (principal == null || principal.tenantId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TENANT_REQUIRED",
                    "订阅必须绑定租户：认证身份未绑定 tenant_id");
        }
        return principal;
    }

    private void validateCallbackUrl(String callbackUrl) {
        URI uri;
        try {
            uri = URI.create(callbackUrl.trim());
        } catch (IllegalArgumentException e) {
            throw invalidCallbackUrl();
        }
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())
                || uri.getHost() == null || uri.getHost().isBlank()) {
            throw invalidCallbackUrl();
        }
    }

    private static ApiException invalidCallbackUrl() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CALLBACK_URL",
                "callback_url 必须是合法的 http(s) URL");
    }

    private static String generateSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String toJson(List<String> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(filters);
        } catch (JsonProcessingException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILTER",
                    "filter 序列化失败");
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("订阅 filter 反序列化失败", e);
        }
    }

    private String nowIso() {
        return OffsetDateTime.now(clock).toString();
    }
}
