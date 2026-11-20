package com.xiye.videoasset.media.extraction;

import com.xiye.videoasset.api.extraction.ExtractionPlan;
import com.xiye.videoasset.api.extraction.ExtractionType;
import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import com.xiye.videoasset.common.Result;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MultipartFile;

@RestController
class ExtractionMediaController {
    private final AutomaticUpload uploads;
    private final ExtractionPlan plan;
    private final EpisodeAssets assets;

    ExtractionMediaController(AutomaticUpload uploads, ExtractionPlan plan, EpisodeAssets assets) {
        this.uploads = uploads;
        this.plan = plan;
        this.assets = assets;
    }

    @GetMapping("/extraction-capabilities")
    ResponseEntity<Result<List<Capability>>> capabilities() {
        List<Capability> capabilities = Arrays.stream(ExtractionType.values())
                .map(type -> new Capability(type, plan.supports(type))).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.ok(capabilities));
    }

    @PostMapping(value = "/videos/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Result<VideoUploadVO>> upload(
            @AuthenticationPrincipal Jwt jwt, @RequestPart("file") List<MultipartFile> files,
            @RequestParam @Positive long projectId, @RequestParam @Positive @Max(4294967295L) long episodeNumber,
            @RequestParam(defaultValue = "false") boolean autoExtract,
            @RequestParam(required = false) List<ExtractionType> extractTypes,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{1,100}") String key
    ) {
        if (files.size() != 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        AutomaticUpload.Outcome result = uploads.upload(jwt.getSubject(), projectId, episodeNumber, key,
                files.getFirst(), autoExtract, extractTypes);
        return ResponseEntity.status(result.created() ? 201 : 200).cacheControl(CacheControl.noStore())
                .body(Result.ok(result.view()));
    }

    @GetMapping("/episodes/{episodeId}/summary")
    ResponseEntity<Result<EpisodeAssets.Summary>> summary(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long episodeId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok(assets.summary(jwt.getSubject(), episodeId)));
    }

    @GetMapping("/episodes/{episodeId}/assets")
    ResponseEntity<Result<EpisodeAssets.Page>> assets(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long episodeId,
            @RequestParam ExtractionType type, @RequestParam(defaultValue = "1") @Positive int pageNum,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int pageSize,
            @RequestParam(required = false) @Positive Long sourceTaskId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok(assets.list(jwt.getSubject(), episodeId, type, pageNum, pageSize, sourceTaskId)));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Result<Void>> invalidInput() {
        return ResponseEntity.badRequest().body(Result.error(ErrorCode.INVALID_REQUEST));
    }

    record Capability(ExtractionType type, boolean supported) {
    }
}
