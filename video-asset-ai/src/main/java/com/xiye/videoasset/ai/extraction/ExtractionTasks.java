package com.xiye.videoasset.ai.extraction;

import com.xiye.videoasset.ai.analysis.service.EpisodeAnalysisAccess;
import com.xiye.videoasset.ai.extraction.ExtractionTaskMapper.EpisodeSource;
import com.xiye.videoasset.ai.extraction.ExtractionTaskMapper.Step;
import com.xiye.videoasset.api.extraction.AiTaskStatus;
import com.xiye.videoasset.api.extraction.ExtractionJob;
import com.xiye.videoasset.api.extraction.ExtractionJobState;
import com.xiye.videoasset.api.extraction.ExtractionJobWriter;
import com.xiye.videoasset.api.extraction.ExtractionJobs;
import com.xiye.videoasset.api.extraction.ExtractionPlan;
import com.xiye.videoasset.api.extraction.ExtractionType;
import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ExtractionTasks {
    private static final Logger LOG = LoggerFactory.getLogger(ExtractionTasks.class);
    private final ExtractionTaskMapper tasks;
    private final ExtractionJobWriter writer;
    private final ExtractionJobs jobs;
    private final EpisodeAnalysisAccess access;
    private final ExtractionPlan plan;

    ExtractionTasks(
            ExtractionTaskMapper tasks, ExtractionJobWriter writer, ExtractionJobs jobs,
            EpisodeAnalysisAccess access, ExtractionPlan plan
    ) {
        this.tasks = tasks;
        this.writer = writer;
        this.jobs = jobs;
        this.access = access;
        this.plan = plan;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ExtractionTaskVO create(String subject, CreateExtractionTask request, String idempotencyKey) {
        EpisodeSource source = tasks.findSource(request.episodeId());
        if (source == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        access.requireUploadedEpisode(subject, source.projectId(), request.episodeId());
        List<ExtractionType> types = plan.validate(request.extractTypes());
        if (types.stream().anyMatch(type -> !plan.supports(type))) {
            throw new BusinessException(ErrorCode.EXTRACTION_UNSUPPORTED);
        }
        if (request.regenerate() && idempotencyKey == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        String key = idempotencyKey == null ? "types:" + ExtractionPlan.signature(types) : "request:" + idempotencyKey;
        ExtractionJob task = jobs.create(source.projectId(), request.episodeId(), source.sourceAssetId(),
                key, types, request.regenerate());
        if (task == null) {
            throw new BusinessException(ErrorCode.EXTRACTION_TASK_CONFLICT);
        }
        LOG.info("已受理素材提取 taskId={} projectId={} episodeId={} types={}",
                task.id(), task.projectId(), task.episodeId(), task.typeSignature());
        return view(writer.state(task.id()));
    }

    @Transactional(readOnly = true)
    public ExtractionTaskVO detail(String subject, long taskId) {
        return view(owned(subject, taskId));
    }

    @Transactional(readOnly = true)
    public ExtractionTaskVO.Page list(
            String subject, Long projectId, Long episodeId, AiTaskStatus status, int pageNum, int pageSize
    ) {
        long owner = access.requireActiveUser(subject);
        long skip = (long) (pageNum - 1) * pageSize;
        List<ExtractionTaskVO> result = tasks.list(owner, projectId, episodeId, status, pageSize, skip).stream()
                .map(ExtractionTasks::summary).toList();
        return new ExtractionTaskVO.Page(result, pageNum, pageSize, tasks.count(owner, projectId, episodeId, status));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ExtractionTaskVO retry(String subject, long taskId) {
        owned(subject, taskId);
        tasks.lock(taskId);
        List<Step> steps = tasks.steps(taskId);
        boolean active = steps.stream().anyMatch(step ->
                step.status() == AiTaskStatus.PENDING || step.status() == AiTaskStatus.RUNNING);
        List<Step> failed = steps.stream().filter(step -> step.status() == AiTaskStatus.FAILED).toList();
        if (!active && !failed.isEmpty()) {
            if (failed.stream().anyMatch(step -> !plan.supports(step.type()))) {
                throw new BusinessException(ErrorCode.EXTRACTION_UNSUPPORTED);
            }
            // 保留成功步骤和已发布素材。重试只解绑失败步骤并将同一主任务重新投递。
            failed.forEach(step -> tasks.resetStep(taskId, step.type()));
            tasks.requeue(taskId);
            LOG.info("素材提取任务已安排重试 taskId={} steps={}", taskId, failed.size());
        }
        return view(writer.state(taskId));
    }

    private ExtractionJobState owned(String subject, long taskId) {
        ExtractionJobState state = tasks.owned(access.requireActiveUser(subject), taskId);
        if (state == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return state;
    }

    private ExtractionTaskVO view(ExtractionJobState state) {
        List<ExtractionTaskVO.Step> steps = tasks.steps(state.id()).stream()
                .map(step -> new ExtractionTaskVO.Step(step.type(), step.status(), progress(step.status()),
                        step.stage(), step.assetCount(), step.errorCode(),
                        step.workerTaskId() == null ? null : step.workerTaskId().toString()))
                .toList();
        return view(state, steps);
    }

    private static ExtractionTaskVO summary(ExtractionJobState state) {
        return view(state, null);
    }

    private static ExtractionTaskVO view(ExtractionJobState state, List<ExtractionTaskVO.Step> steps) {
        return new ExtractionTaskVO(Long.toString(state.id()), Long.toString(state.projectId()),
                Long.toString(state.episodeId()), state.status(), progress(state.status()),
                state.createdAt(), state.startedAt(), state.finishedAt(), steps);
    }

    private static Integer progress(AiTaskStatus status) {
        return status == AiTaskStatus.SUCCESS ? 100 : null;
    }
}
