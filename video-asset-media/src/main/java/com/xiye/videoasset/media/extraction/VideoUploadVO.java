package com.xiye.videoasset.media.extraction;

import com.xiye.videoasset.api.extraction.AiTaskStatus;

public record VideoUploadVO(
        String videoId, String episodeId, boolean autoExtract, String taskId,
        AiTaskStatus taskStatus, String taskErrorCode
) {
}
