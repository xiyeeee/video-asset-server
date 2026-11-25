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
    INVALID_VIDEO(400, "请选择有效的 MP4 视频文件"),
    INVALID_IMAGE(400, "请选择有效的 PNG、JPEG 或 WebP 图片"),
    IMAGE_TOO_LARGE(413, "图片超过 10 MiB"),
    VIDEO_TOO_LARGE(413, "视频文件超过上传大小限制"),
    VIDEO_UPLOAD_CONFLICT(409, "上传标识与已有视频不匹配"),
    EPISODE_NUMBER_CONFLICT(409, "当前项目已使用该集号"),
    EXTRACTION_UNSUPPORTED(422, "包含服务尚不支持的提取类型"),
    EXTRACTION_TASK_CONFLICT(409, "任务标识已绑定其他提取参数"),
    LIVE2D_SAVE_NOT_RETRYABLE(409, "当前任务没有可重试保存的模型包"),
    LIVE2D_CHARACTER_BUSY(409, "角色仍有生成任务进行中，请稍后再删除"),
    LIVE2D_COMBO_INVALID(400, "组合动作时间轴或来源素材无效"),
    LIVE2D_COMBO_CONFLICT(409, "组合动作内容已变化或与当前模型版本不匹配"),
    LIVE2D_COMBO_NOT_FOUND(404, "组合动作或来源素材不存在"),
    LIVE2D_COMBO_SAVE_IN_PROGRESS(409, "组合动作正在保存，请稍后查询"),
    LIVE2D_COMBO_SAVE_FAILED(503, "组合动作保存失败，可使用原请求标识重试"),
    LIVE2D_COMBO_COMPILER_UNAVAILABLE(503, "组合动作编译服务暂不可用"),
    VIDEO_STORAGE_UNAVAILABLE(503, "视频存储暂不可用，请稍后重试"),
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
