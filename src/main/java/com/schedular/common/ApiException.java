package com.schedular.common;

import org.springframework.http.HttpStatus;

/** 业务异常：携带 HTTP 状态与机器码，由 ApiExceptionHandler 统一转 ApiError。 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
