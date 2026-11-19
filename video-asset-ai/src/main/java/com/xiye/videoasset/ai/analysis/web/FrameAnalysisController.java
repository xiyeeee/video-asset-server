package com.xiye.videoasset.ai.analysis.web;

import com.xiye.videoasset.ai.analysis.model.FrameTaskVO;
import com.xiye.videoasset.ai.analysis.service.FrameAnalysisService;
import com.xiye.videoasset.ai.analysis.service.FrameAnalysisService.FramePreview;
import com.xiye.videoasset.common.ErrorCode;
import com.xiye.videoasset.common.Result;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestController
@RequestMapping("/v1/projects/{projectId}/episodes/{episodeId}")
class FrameAnalysisController {
    private final FrameAnalysisService analysis;

    FrameAnalysisController(FrameAnalysisService analysis) {
        this.analysis = analysis;
    }

    @PostMapping("/analysis")
    ResponseEntity<Result<FrameTaskVO>> start(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Positive long episodeId
    ) {
        return taskResponse(analysis.start(jwt.getSubject(), projectId, episodeId));
    }

    @GetMapping("/analysis")
    ResponseEntity<Result<FrameTaskVO>> current(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Positive long episodeId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok(analysis.current(jwt.getSubject(), projectId, episodeId)));
    }

    @PostMapping("/analysis/{taskId}/retry")
    ResponseEntity<Result<FrameTaskVO>> retry(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Positive long episodeId, @PathVariable @Positive long taskId
    ) {
        return taskResponse(analysis.retry(jwt.getSubject(), projectId, episodeId, taskId));
    }

    @GetMapping("/frames")
    ResponseEntity<Result<List<FramePreview>>> frames(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Positive long episodeId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok(analysis.frames(jwt.getSubject(), projectId, episodeId)));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Result<Void>> invalidInput() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Result.error(ErrorCode.INVALID_REQUEST));
    }

    private static ResponseEntity<Result<FrameTaskVO>> taskResponse(FrameTaskVO task) {
        int httpStatus = switch (task.status()) {
            case "PENDING", "RUNNING" -> 202;
            default -> 200;
        };
        return ResponseEntity.status(httpStatus).cacheControl(CacheControl.noStore()).body(Result.ok(task));
    }
}
