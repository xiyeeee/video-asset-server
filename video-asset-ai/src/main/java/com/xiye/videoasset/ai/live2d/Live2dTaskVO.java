package com.xiye.videoasset.ai.live2d;

public record Live2dTaskVO(String taskId, Live2dTask.Status status, String stage, Result result, Error error) {
    public record Result(String modelId, String modelEntryUrl) {
        @Override
        public String toString() {
            return "Result[modelId=" + modelId + "]";
        }
    }

    public record Error(String code, String message, String stage) {
    }
}
