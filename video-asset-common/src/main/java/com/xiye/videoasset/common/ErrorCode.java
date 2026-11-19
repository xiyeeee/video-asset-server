package com.xiye.videoasset.common;

public enum ErrorCode {
    INVALID_REQUEST(400, "Invalid request"),
    UNAUTHENTICATED(401, "Authentication required"),
    FORBIDDEN(403, "Access denied"),
    NOT_FOUND(404, "Resource not found"),
    INVALID_CREDENTIALS(401, "账号或密码错误"),
    VERIFICATION_INVALID(400, "验证码无效或已过期，请重新获取"),
    ACCOUNT_CONFLICT(409, "邮箱或用户名已被使用"),
    RATE_LIMITED(429, "操作过于频繁，请稍后重试"),
    MAIL_UNAVAILABLE(503, "邮件服务暂不可用，请稍后重试"),
    AUTH_UNAVAILABLE(503, "认证服务暂不可用，请稍后重试"),
    INTERNAL_ERROR(500, "Internal server error");

    private final int status;
    private final String message;

    ErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    public int status() {
        return status;
    }

    public String message() {
        return message;
    }
}
