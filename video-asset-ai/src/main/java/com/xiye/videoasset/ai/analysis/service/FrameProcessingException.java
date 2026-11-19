package com.xiye.videoasset.ai.analysis.service;

class FrameProcessingException extends RuntimeException {
    private final String code;

    FrameProcessingException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    FrameProcessingException(String code) {
        this(code, null);
    }

    String code() {
        return code;
    }
}
