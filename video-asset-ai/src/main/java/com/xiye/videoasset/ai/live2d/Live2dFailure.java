package com.xiye.videoasset.ai.live2d;

final class Live2dFailure extends RuntimeException {
    Live2dFailure(String code) {
        super(code);
    }

    static String message(String code) {
        return switch (code) {
            case "CONVERTER_UNAVAILABLE", "CUBISM_CORE_UNAVAILABLE", "IMAGE_DECODER_UNAVAILABLE" ->
                    "模型生成服务暂不可用，请联系管理员";
            case "CONVERTER_TIMEOUT", "LIVE2D_TASK_TIMEOUT" -> "模型生成超时，请稍后重新提交";
            case "SOURCE_IMAGE_INVALID", "SOURCE_IMAGE_REF_INVALID", "SOURCE_IMAGE_NOT_FOUND" ->
                    "找不到源图片或图片无法读取，请重新上传";
            case "ARTIFACT_MISSING", "ARTIFACT_WRITE_FAILED" -> "生成服务未发布完整的模型包";
            case "ARTIFACT_INVALID", "RUNTIME_INVALID", "RUNTIME_NOT_EXPORTED", "MOC3_VALIDATION_FAILED" ->
                    "模型包不完整或无法加载";
            case "PYTHON_TASK_LOST" -> "模型生成服务重启后未恢复任务，请重新提交";
            case "PYTHON_PROTOCOL_INVALID", "TASK_CONFLICT" -> "生成服务返回异常，请联系管理员";
            default -> "图片转 Live2D 失败，请稍后重试";
        };
    }
}
