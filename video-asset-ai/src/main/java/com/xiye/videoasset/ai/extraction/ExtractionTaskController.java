package com.xiye.videoasset.ai.extraction;

import com.xiye.videoasset.api.extraction.AiTaskStatus;
import com.xiye.videoasset.common.ErrorCode;
import com.xiye.videoasset.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestController
@RequestMapping("/tasks")
class ExtractionTaskController {
    private final ExtractionTasks tasks;

    ExtractionTaskController(ExtractionTasks tasks) {
        this.tasks = tasks;
    }

    @PostMapping
    ResponseEntity<Result<ExtractionTaskVO>> create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateExtractionTask request,
            @RequestHeader(value = "Idempotency-Key", required = false)
            @Pattern(regexp = "[A-Za-z0-9._:-]{1,100}") String key
    ) {
        return submit(tasks.create(jwt.getSubject(), request, key));
    }

    @GetMapping
    ResponseEntity<Result<ExtractionTaskVO.Page>> list(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) @Positive Long projectId,
            @RequestParam(required = false) @Positive Long episodeId,
            @RequestParam(required = false) AiTaskStatus status,
            @RequestParam(defaultValue = "1") @Positive int pageNum,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize
    ) {
        return response(tasks.list(jwt.getSubject(), projectId, episodeId, status, pageNum, pageSize));
    }

    @GetMapping("/{taskId}")
    ResponseEntity<Result<ExtractionTaskVO>> task(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long taskId
    ) {
        return response(tasks.detail(jwt.getSubject(), taskId));
    }

    @GetMapping("/{taskId}/steps")
    ResponseEntity<Result<List<ExtractionTaskVO.Step>>> steps(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long taskId
    ) {
        return response(tasks.detail(jwt.getSubject(), taskId).steps());
    }

    @PostMapping("/{taskId}/retry")
    ResponseEntity<Result<ExtractionTaskVO>> retry(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long taskId
    ) {
        return submit(tasks.retry(jwt.getSubject(), taskId));
    }

    @ExceptionHandler({HandlerMethodValidationException.class,
            MethodArgumentNotValidException.class})
    ResponseEntity<Result<Void>> invalid() {
        return ResponseEntity.badRequest().body(Result.error(ErrorCode.INVALID_REQUEST));
    }

    private static <T> ResponseEntity<Result<T>> response(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.ok(value));
    }

    private static ResponseEntity<Result<ExtractionTaskVO>> submit(ExtractionTaskVO task) {
        int status = switch (task.status()) {
            case PENDING, RUNNING -> 202;
            default -> 200;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Result.ok(task));
    }
}
