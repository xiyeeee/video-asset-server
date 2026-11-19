package com.xiye.videoasset.ai.analysis.service;

import com.xiye.videoasset.ai.analysis.model.FrameTaskVO;
import com.xiye.videoasset.ai.analysis.persistence.FrameTaskRepository;
import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FrameAnalysisService {
    private static final Logger LOG = LoggerFactory.getLogger(FrameAnalysisService.class);
    private final FrameTaskRepository tasks;
    private final FrameStorage storage;

    FrameAnalysisService(FrameTaskRepository tasks, FrameStorage storage) {
        this.tasks = tasks;
        this.storage = storage;
    }

    public FrameTaskVO start(String subject, long projectId, long episodeId) {
        authorize(subject, projectId, episodeId);
        tasks.create(episodeId);
        FrameTaskVO task = tasks.find(episodeId).orElseThrow();
        LOG.info("已受理首帧解析请求 taskId={} episodeId={} status={}", task.id(), episodeId, task.status());
        return task;
    }

    public FrameTaskVO current(String subject, long projectId, long episodeId) {
        authorize(subject, projectId, episodeId);
        return tasks.find(episodeId).orElse(null);
    }

    public FrameTaskVO retry(String subject, long projectId, long episodeId, long taskId) {
        authorize(subject, projectId, episodeId);
        FrameTaskVO current = tasks.find(episodeId)
                .filter(task -> task.id().equals(Long.toString(taskId)))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if ("FAILED".equals(current.status())) {
            tasks.retry(episodeId, taskId, current.attemptCount());
            return tasks.find(episodeId).orElseThrow();
        }
        return current;
    }

    public List<FramePreview> frames(String subject, long projectId, long episodeId) {
        authorize(subject, projectId, episodeId);
        return tasks.frame(projectId, episodeId).stream()
                .map(frame -> new FramePreview(frame.id(), "首帧分镜", "storyboard", frame.timestampMs(),
                        frame.sizeBytes(), storage.sign(frame), FrameStorage.PREVIEW_TTL_SECONDS))
                .toList();
    }

    private void authorize(String subject, long projectId, long episodeId) {
        long ownerId;
        try {
            ownerId = Long.parseLong(subject);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        if (ownerId <= 0 || !tasks.isActive(ownerId)) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        if (!tasks.ownsSource(ownerId, projectId, episodeId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    public record FramePreview(String id, String name, String type, long timestampMs, long sizeBytes,
                               String url, int expiresInSeconds) {
        @Override
        public String toString() {
            return "FramePreview[id=" + id + "]";
        }
    }
}
