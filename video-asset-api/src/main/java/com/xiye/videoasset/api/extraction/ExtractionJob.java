package com.xiye.videoasset.api.extraction;

import java.time.Instant;

public record ExtractionJob(
        long id, long projectId, long episodeId, long sourceAssetId, String typeSignature, boolean regenerate,
        int dispatchAttempts, Instant dispatchedAt, Instant dispatchFailedAt, Instant createdAt
) {
}
