package com.xiye.videoasset.media.episode.model;

import java.time.Instant;

public record EpisodeVO(
        String id,
        String projectId,
        long episodeNumber,
        String title,
        String status,
        String originalFilename,
        String contentType,
        long sizeBytes,
        Long durationMs,
        Instant uploadedAt
) {
}
