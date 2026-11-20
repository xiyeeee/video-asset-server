package com.xiye.videoasset.ai.characters.persistence;

import java.time.Instant;

public record CharacterTaskEntity(
        long id, long episodeId, String status, int attemptCount, long leaseVersion, long sourceAssetId,
        double intervalSeconds, String stage, Integer frameCount, Integer detectedCharacterCount,
        String manifestRef, Integer assetCount, String errorCode, Instant createdAt, Instant finishedAt
) {
}
