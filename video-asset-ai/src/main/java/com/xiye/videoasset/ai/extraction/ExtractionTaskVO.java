package com.xiye.videoasset.ai.extraction;

import com.xiye.videoasset.api.extraction.AiTaskStatus;
import com.xiye.videoasset.api.extraction.ExtractionType;
import java.time.Instant;
import java.util.List;

record ExtractionTaskVO(
        String taskId, String projectId, String episodeId, AiTaskStatus status, Integer progress,
        Instant createdAt, Instant startedAt, Instant finishedAt, List<Step> steps
) {
    record Step(
            ExtractionType type, AiTaskStatus status, Integer progress, String stage, Integer assetCount,
            String errorCode, String sourceTaskId
    ) {
    }

    record Page(List<ExtractionTaskVO> items, int pageNum, int pageSize, long total) {
    }
}
