package com.xiye.videoasset.ai.characters.model;

import java.time.Instant;

public record CharacterTaskVO(
        String id, String episodeId, String status, String stage, Progress progress,
        Integer assetCount, String errorCode, Instant createdAt, Instant finishedAt
) {
    public record Progress(Integer frameCount, Integer detectedCharacterCount) {
    }
}
