package com.xiye.videoasset.common;

public record Result<T>(String code, String message, T data) {
    public static <T> Result<T> ok(T data) {
        return new Result<>("OK", "Success", data);
    }

    public static Result<Void> error(ErrorCode errorCode) {
        return new Result<>(errorCode.name(), errorCode.message(), null);
    }
}
