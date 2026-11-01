package com.schedular.security;

import com.schedular.common.ApiError;
import com.schedular.storage.ApiKeyRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 所有 /api/** 的静态 API Key 过滤器：X-API-Key → SHA-256 hex → 查 api_keys（常量时间比较）。
 * 缺失/无效 → 401 UNAUTHORIZED；/api/agents/** 需要 ADMIN，否则 403 FORBIDDEN。
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-API-Key";
    private static final String AGENTS_PREFIX = "/api/agents";

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyFilter(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 回调端点以 X-Callback-Token 为凭证，不走 X-API-Key 过滤器
        if (request.getRequestURI().matches("/api/agent-executions/[^/]+/result")) {
            return true;
        }
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented == null || presented.isBlank()) {
            writeError(response, 401, "UNAUTHORIZED", "缺少 X-API-Key 请求头");
            return;
        }
        String computedHash = sha256Hex(presented);
        var apiKey = apiKeyRepository.findByKeyHash(computedHash)
                .filter(k -> constantTimeEquals(k.keyHash(), computedHash) && k.enabled());
        if (apiKey.isEmpty()) {
            writeError(response, 401, "UNAUTHORIZED", "无效或已停用的 API Key");
            return;
        }
        if (request.getRequestURI().startsWith(AGENTS_PREFIX)
                && !TenantPrincipal.ROLE_ADMIN.equals(apiKey.get().role())) {
            writeError(response, 403, "FORBIDDEN", "访问 /api/agents/** 需要 ADMIN 角色");
            return;
        }
        var principal = new TenantPrincipal(
                apiKey.get().callerName(), apiKey.get().role(), apiKey.get().tenantId());
        TenantContext.set(principal);
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                """
                {"code":"%s","message":"%s"}""".formatted(code, message));
    }
}
