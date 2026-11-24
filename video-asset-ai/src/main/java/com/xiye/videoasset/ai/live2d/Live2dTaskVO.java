package com.xiye.videoasset.ai.live2d;

public record Live2dTaskVO(
        String taskId, Live2dTask.Status status, String stage, Result result, Error error,
        SaveStatus saveStatus, String saveErrorCode
) {
    public enum SaveStatus {
        NOT_STARTED, SAVING, SAVED, FAILED
    }

    public record Result(String modelId, String modelEntryUrl, String characterId, String versionId) {
        @Override
        public String toString() {
            return "Result[modelId=" + modelId + "]";
        }
    }

    public record Error(String code, String message, String stage) {
    }
}
