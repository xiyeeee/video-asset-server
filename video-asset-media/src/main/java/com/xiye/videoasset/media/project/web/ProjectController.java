package com.xiye.videoasset.media.project.web;

import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import com.xiye.videoasset.common.Result;
import com.xiye.videoasset.media.project.model.ProjectVO;
import com.xiye.videoasset.media.project.service.ProjectService;
import com.xiye.videoasset.media.project.service.ProjectService.Page;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestController
@RequestMapping("/v1/projects")
class ProjectController {
    private final ProjectService projects;

    ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    @GetMapping
    ResponseEntity<Result<Page>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "") @Size(max = 100) String q,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok(projects.list(jwt.getSubject(), q, page, size)));
    }

    @PostMapping
    ResponseEntity<Result<ProjectVO>> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRequest body) {
        ProjectVO project = projects.create(jwt.getSubject(), body.name(), body.description());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(Result.ok(project));
    }

    @GetMapping("/{projectId}")
    ResponseEntity<Result<ProjectVO>> get(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok(projects.get(jwt.getSubject(), projectId)));
    }

    @PatchMapping("/{projectId}")
    ResponseEntity<Result<ProjectVO>> patch(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @Valid @RequestBody PatchRequest body
    ) {
        if (body.name() == null && body.description() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok(projects.update(jwt.getSubject(), projectId, body.name(), body.description())));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class})
    ResponseEntity<Result<Void>> invalidInput() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Result.error(ErrorCode.INVALID_REQUEST));
    }

    record CreateRequest(@NotBlank @Size(min = 2, max = 30) String name,
                         @NotBlank @Size(min = 4, max = 200) String description) {
        CreateRequest {
            name = strip(name);
            description = strip(description);
        }
    }

    record PatchRequest(@Size(min = 2, max = 30) String name, @Size(min = 4, max = 200) String description) {
        PatchRequest {
            name = strip(name);
            description = strip(description);
        }
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }
}
