package com.schedular.common;

/** 统一错误体：{ "code": "<MACHINE_CODE>", "message": "<中文可读>" }。 */
public record ApiError(String code, String message) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message);
    }
}
