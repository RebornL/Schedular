package com.schedular.security;

/** 请求级认证身份，来源于 X-API-Key 对应的 api_keys 行。 */
public record TenantPrincipal(String callerName, String role, String tenantId) {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_CALLER = "CALLER";

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }
}
