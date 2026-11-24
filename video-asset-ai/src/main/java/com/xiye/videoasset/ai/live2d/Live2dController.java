package com.xiye.videoasset.ai.live2d;

import com.xiye.videoasset.ai.live2d.Live2dService.ServedFile;
import com.xiye.videoasset.common.ErrorCode;
import com.xiye.videoasset.common.Result;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestController
@RequestMapping("/projects/{projectId}")
class Live2dController {
    private final Live2dService service;

    Live2dController(Live2dService service) {
        this.service = service;
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Result<Live2dService.Upload>> upload(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @RequestPart("file") MultipartFile file
    ) {
        return ResponseEntity.status(201).body(Result.ok(service.upload(jwt.getSubject(), projectId, file)));
    }

    @PostMapping("/live2d/tasks")
    ResponseEntity<Result<Live2dTaskVO>> create(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @RequestBody Live2dService.Create request
    ) {
        Live2dTaskVO task = service.start(jwt.getSubject(), projectId, request);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(Result.ok(task));
    }

    @GetMapping("/live2d/tasks/{taskId}")
    ResponseEntity<Result<Live2dTaskVO>> query(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Pattern(regexp = "[a-f0-9]{32}") String taskId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok(service.query(jwt.getSubject(), projectId, taskId)));
    }

    @GetMapping("/live2d/models/{modelId}/files/{ticket}/{*path}")
    ResponseEntity<InputStreamResource> resource(
            @PathVariable @Positive long projectId, @PathVariable @Positive long modelId,
            @PathVariable @Pattern(regexp = "[a-f0-9]{64}") String ticket, @PathVariable String path
    ) {
        ServedFile file = service.resource(projectId, modelId, ticket, path.startsWith("/") ? path.substring(1) : path);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer").header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(file.contentType())).contentLength(file.size())
                .body(new InputStreamResource(file.content()));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Result<Void>> invalid() {
        return ResponseEntity.badRequest().body(Result.error(ErrorCode.INVALID_REQUEST));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Result<Void>> tooLarge() {
        return ResponseEntity.status(413).body(Result.error(ErrorCode.IMAGE_TOO_LARGE));
    }
}
