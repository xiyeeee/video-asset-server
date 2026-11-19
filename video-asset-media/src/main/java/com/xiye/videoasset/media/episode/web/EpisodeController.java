package com.xiye.videoasset.media.episode.web;

import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import com.xiye.videoasset.common.Result;
import com.xiye.videoasset.media.episode.model.EpisodeVO;
import com.xiye.videoasset.media.episode.service.EpisodeService;
import com.xiye.videoasset.media.episode.service.EpisodeService.Page;
import com.xiye.videoasset.media.episode.service.EpisodeService.Playback;
import com.xiye.videoasset.media.episode.service.EpisodeService.UploadResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/v1/projects/{projectId}/episodes")
class EpisodeController {
    private final EpisodeService episodes;

    EpisodeController(EpisodeService episodes) {
        this.episodes = episodes;
    }

    @GetMapping
    ResponseEntity<Result<Page>> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok(episodes.list(jwt.getSubject(), projectId, page, size)));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Result<EpisodeVO>> upload(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{1,100}") String key,
            @RequestPart("file") List<MultipartFile> files
    ) {
        if (files.size() != 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        UploadResult result = episodes.upload(jwt.getSubject(), projectId, key, files.getFirst());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Result.ok(result.episode()));
    }

    @GetMapping("/{episodeId}/playback")
    ResponseEntity<Result<Playback>> playback(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Positive long episodeId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok(episodes.playback(jwt.getSubject(), projectId, episodeId)));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Result<Void>> invalidInput() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Result.error(ErrorCode.INVALID_REQUEST));
    }
}
