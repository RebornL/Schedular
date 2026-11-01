package com.schedular.security;

/**
 * 请求级租户上下文。ApiKeyFilter 写入，请求结束（RequestAttributes 销毁回调）时清理。
 */
public final class TenantContext {

    private static final ThreadLocal<TenantPrincipal> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(TenantPrincipal principal) {
        HOLDER.set(principal);
    }

    public static TenantPrincipal get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
