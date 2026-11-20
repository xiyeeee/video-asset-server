package com.xiye.videoasset.ai.analysis.service;

import com.xiye.videoasset.ai.analysis.persistence.FrameTaskRepository;
import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class EpisodeAnalysisAccess {
    private final FrameTaskRepository tasks;

    EpisodeAnalysisAccess(FrameTaskRepository tasks) {
        this.tasks = tasks;
    }

    // 视频解析接口统一要求带有效账号的所属项目，以及已绑定的原视频。
    public void requireUploadedEpisode(String subject, long projectId, long episodeId) {
        long ownerId = requireActiveUser(subject);
        if (!tasks.ownsSource(ownerId, projectId, episodeId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    public long requireActiveUser(String subject) {
        long ownerId;
        try {
            ownerId = Long.parseLong(subject);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        if (ownerId <= 0 || !tasks.isActive(ownerId)) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return ownerId;
    }
}
