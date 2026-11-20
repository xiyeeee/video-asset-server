package com.xiye.videoasset.ai.characters.web;

import com.xiye.videoasset.ai.characters.model.CharacterAssetVO;
import com.xiye.videoasset.ai.characters.model.CharacterTaskVO;
import com.xiye.videoasset.ai.characters.service.CharacterExtractionService;
import com.xiye.videoasset.common.ErrorCode;
import com.xiye.videoasset.common.Result;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestController
@RequestMapping("/v1/projects/{projectId}/episodes/{episodeId}")
class CharacterExtractionController {
    private final CharacterExtractionService characters;

    CharacterExtractionController(CharacterExtractionService characters) {
        this.characters = characters;
    }

    @PostMapping("/character-extraction/tasks")
    ResponseEntity<Result<CharacterTaskVO>> start(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Positive long episodeId
    ) {
        return submission(characters.start(jwt.getSubject(), projectId, episodeId));
    }

    @GetMapping("/character-extraction/tasks/latest")
    ResponseEntity<Result<CharacterTaskVO>> latest(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Positive long episodeId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok(characters.latest(jwt.getSubject(), projectId, episodeId)));
    }

    @GetMapping("/character-extraction/tasks/{taskId}")
    ResponseEntity<Result<CharacterTaskVO>> task(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Positive long episodeId, @PathVariable @Positive long taskId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok(characters.task(jwt.getSubject(), projectId, episodeId, taskId)));
    }

    @PostMapping("/character-extraction/tasks/{taskId}/retry")
    ResponseEntity<Result<CharacterTaskVO>> retry(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Positive long episodeId, @PathVariable @Positive long taskId
    ) {
        return submission(characters.retry(jwt.getSubject(), projectId, episodeId, taskId));
    }

    @GetMapping("/character-assets")
    ResponseEntity<Result<CharacterAssetVO.Page>> assets(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Positive long episodeId, @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int size
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.ok(characters.assets(jwt.getSubject(), projectId, episodeId, page, size)));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Result<Void>> invalidInput() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Result.error(ErrorCode.INVALID_REQUEST));
    }

    private static ResponseEntity<Result<CharacterTaskVO>> submission(CharacterTaskVO task) {
        int status = switch (task.status()) {
            case "PENDING", "RUNNING" -> 202;
            default -> 200;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Result.ok(task));
    }
}
