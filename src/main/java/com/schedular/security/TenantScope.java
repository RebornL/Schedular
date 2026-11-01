package com.schedular.security;

import com.schedular.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 查询租户口径统一规则（05：查询全部按 TenantContext 过滤）：
 * CALLER 恒按认证身份租户过滤，显式 tenant 不一致 → 403 TENANT_MISMATCH；
 * ADMIN（无租户绑定）必须显式传 tenant，缺失 → 400 MISSING_TENANT。
 */
public final class TenantScope {

    private TenantScope() {
    }

    public static String resolve(TenantPrincipal principal, String requestedTenant) {
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "缺少认证身份");
        }
        if (principal.isAdmin()) {
            if (requestedTenant == null || requestedTenant.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_TENANT",
                        "ADMIN 查询必须显式指定 tenant 参数");
            }
            return requestedTenant;
        }
        if (requestedTenant != null && !requestedTenant.isBlank()
                && !requestedTenant.equals(principal.tenantId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TENANT_MISMATCH",
                    "tenant 参数与认证身份租户不一致");
        }
        return principal.tenantId();
    }
}
