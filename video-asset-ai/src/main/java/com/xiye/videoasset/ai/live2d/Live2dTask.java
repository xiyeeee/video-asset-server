package com.xiye.videoasset.ai.live2d;

public record Live2dTask(
        String id, long projectId, long sourceAssetId, String modelName, Long modelAssetId,
        Status status, String stage, String manifestPath, String artifactRef,
        String errorCode, String errorStage, int leaseVersion, int submissionCount, Long characterId,
        String historyIssue
) {
    public enum Status {
        QUEUED, RUNNING, SUCCEEDED, FAILED
    }
}
