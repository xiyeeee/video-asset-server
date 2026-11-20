package com.xiye.videoasset.api.extraction;

import java.time.Instant;

public record ExtractionJobState(
        long id, long projectId, long episodeId, Instant createdAt,
        AiTaskStatus status, Instant startedAt, Instant finishedAt
) {
}
