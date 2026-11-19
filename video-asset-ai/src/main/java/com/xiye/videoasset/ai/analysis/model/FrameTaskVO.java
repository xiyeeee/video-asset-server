package com.xiye.videoasset.ai.analysis.model;

import java.time.Instant;

public record FrameTaskVO(
        String id, String episodeId, String status, long attemptCount, String errorCode, String resultAssetId,
        Instant createdAt, Instant startedAt, Instant finishedAt
) {
}
