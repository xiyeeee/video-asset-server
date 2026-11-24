package com.xiye.videoasset.ai.live2d;

import com.xiye.videoasset.ai.live2d.Live2dService.ServedFile;
import com.xiye.videoasset.common.ErrorCode;
import com.xiye.videoasset.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestController
@RequestMapping("/projects/{projectId}")
class Live2dController {
    private final Live2dService service;
    private final Live2dHistory history;

    Live2dController(Live2dService service, Live2dHistory history) {
        this.service = service;
        this.history = history;
    }

    @PostMapping("/live2d/tasks/{taskId}/retry-save")
    ResponseEntity<Result<Live2dTaskVO>> retrySave(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Pattern(regexp = "[a-f0-9]{32}") String taskId
    ) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(Result.ok(service.retrySave(jwt.getSubject(), projectId, taskId)));
    }

    @GetMapping("/live2d/characters")
    ResponseEntity<Result<Live2dHistory.Page<Live2dHistory.CharacterView>>> characters(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int size
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok(history.characters(jwt.getSubject(), projectId, page, size)));
    }

    @GetMapping("/live2d/characters/{characterId}/versions")
    ResponseEntity<Result<Live2dHistory.Page<Live2dHistory.VersionView>>> versions(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Positive long characterId, @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int size
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok(history.versions(jwt.getSubject(), projectId, characterId, page, size)));
    }

    @PatchMapping("/live2d/characters/{characterId}")
    ResponseEntity<Result<Void>> renameCharacter(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Positive long characterId, @Valid @RequestBody RenameCharacter request
    ) {
        history.renameCharacter(jwt.getSubject(), projectId, characterId, request.name());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.ok(null));
    }

    @DeleteMapping("/live2d/characters/{characterId}")
    ResponseEntity<Result<Void>> deleteCharacter(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Positive long characterId
    ) {
        history.deleteCharacter(jwt.getSubject(), projectId, characterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.ok(null));
    }

    @GetMapping("/live2d/characters/{characterId}/versions/{versionId}")
    ResponseEntity<Result<Live2dHistory.Detail>> version(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Positive long characterId, @PathVariable @Positive long versionId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok(history.detail(jwt.getSubject(), projectId, characterId, versionId)));
    }

    @GetMapping("/live2d/characters/{characterId}/versions/{versionId}/download")
    ResponseEntity<InputStreamResource> download(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Positive long characterId, @PathVariable @Positive long versionId
    ) {
        ServedFile file = history.download(jwt.getSubject(), projectId, characterId, versionId);
        String disposition = ContentDisposition.attachment().filename("live2d-" + versionId + ".zip")
                .build().toString();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition).contentLength(file.size())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(new InputStreamResource(file.content()));
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

    record RenameCharacter(@NotBlank @Size(max = 150) String name) {
    }
}
