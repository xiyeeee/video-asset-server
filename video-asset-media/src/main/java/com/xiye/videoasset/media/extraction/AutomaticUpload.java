package com.xiye.videoasset.media.extraction;

import com.xiye.videoasset.api.extraction.AiTaskStatus;
import com.xiye.videoasset.api.extraction.ExtractionJob;
import com.xiye.videoasset.api.extraction.ExtractionJobState;
import com.xiye.videoasset.api.extraction.ExtractionJobWriter;
import com.xiye.videoasset.api.extraction.ExtractionJobs;
import com.xiye.videoasset.api.extraction.ExtractionPlan;
import com.xiye.videoasset.api.extraction.ExtractionType;
import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import com.xiye.videoasset.media.episode.service.EpisodeService;
import com.xiye.videoasset.media.episode.service.EpisodeService.UploadResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
class AutomaticUpload {
    private static final Logger LOG = LoggerFactory.getLogger(AutomaticUpload.class);
    private final EpisodeService episodes;
    private final ExtractionJobs jobs;
    private final ExtractionJobWriter tasks;
    private final ExtractionPlan plan;
    private final TransactionTemplate transaction;

    AutomaticUpload(
            EpisodeService episodes, ExtractionJobs jobs, ExtractionJobWriter tasks,
            ExtractionPlan plan, TransactionTemplate transaction
    ) {
        this.episodes = episodes;
        this.jobs = jobs;
        this.tasks = tasks;
        this.plan = plan;
        this.transaction = transaction;
    }

    Outcome upload(
            String subject, long projectId, long episodeNumber, String key,
            MultipartFile file, boolean autoExtract, List<ExtractionType> requested
    ) {
        List<ExtractionType> types = List.of();
        if (autoExtract) {
            try {
                types = plan.validate(requested);
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            if (types.stream().anyMatch(type -> !plan.supports(type))) {
                throw new BusinessException(ErrorCode.EXTRACTION_UNSUPPORTED);
            }
        }
        String signature = ExtractionPlan.signature(types);
        UploadResult upload = episodes.upload(subject, projectId, key, file, episodeNumber,
                autoExtract ? signature : "OFF");
        String videoId = Long.toString(upload.videoId());
        String episodeId = upload.episode().id();
        if (!autoExtract) {
            return new Outcome(new VideoUploadVO(videoId, episodeId, false, null, null, null), upload.created());
        }

        // 视频已经独立提交。任务表暂不可用时，重复上传同一个幂等键或从任务中心可以补建。
        try {
            List<ExtractionType> selected = types;
            ExtractionJobState task = transaction.execute(status -> {
                ExtractionJob job = jobs.create(projectId, Long.parseLong(episodeId), upload.videoId(),
                        "types:" + signature, selected, false);
                if (job == null) {
                    throw new IllegalStateException("自动任务参数冲突");
                }
                return tasks.state(job.id());
            });
            if (task == null) {
                throw new IllegalStateException("无法读取已创建的自动任务");
            }
            LOG.info("上传关联提取任务 taskId={} projectId={} episodeId={} types={}",
                    task.id(), projectId, episodeId, signature);
            VideoUploadVO view = new VideoUploadVO(videoId, episodeId, true, Long.toString(task.id()),
                    task.status(), null);
            return new Outcome(view, upload.created());
        } catch (DataAccessException | TransactionException | IllegalStateException exception) {
            LOG.warn("视频已保存，自动任务创建或查询失败 projectId={} episodeId={} exceptionType={}",
                    projectId, episodeId, exception.getClass().getSimpleName());
            return new Outcome(new VideoUploadVO(videoId, episodeId, true, null, AiTaskStatus.FAILED,
                    "EXTRACTION_TASK_CREATE_FAILED"), upload.created());
        }
    }

    record Outcome(VideoUploadVO view, boolean created) {
    }
}
