package com.xiye.videoasset.ai.live2d;

import com.xiye.videoasset.common.ErrorCode;
import com.xiye.videoasset.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestController
@RequestMapping("/projects/{projectId}/live2d")
class Live2dMotionController {
    private final Live2dMotionService motions;

    Live2dMotionController(Live2dMotionService motions) {
        this.motions = motions;
    }

    @GetMapping("/motions")
    ResponseEntity<Result<Live2dMotionService.Page<Live2dMotionService.ActionSummary>>> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @RequestParam(required = false) @Pattern(regexp = "BASIC|COMBO|CUSTOM|DYNAMIC") String type,
            @RequestParam(required = false) @Pattern(regexp = "[a-z][a-z0-9_-]{0,47}") String category,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false)
            @Pattern(regexp = "COMPATIBLE|PARTIAL|INCOMPATIBLE") String compatibilityStatus,
            @RequestParam(required = false) @Positive Long modelVersionId,
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int size
    ) {
        return noStore(Result.ok(motions.list(jwt.getSubject(), projectId, type, category, keyword,
                compatibilityStatus, modelVersionId == null ? null : Long.toString(modelVersionId), page, size)));
    }

    @PostMapping("/motions")
    ResponseEntity<Result<Live2dMotionService.ActionDetail>> create(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @Valid @RequestBody Live2dMotionService.CreateRequest request
    ) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(Result.ok(motions.create(jwt.getSubject(), projectId, request)));
    }

    @GetMapping("/motions/{motionId}")
    ResponseEntity<Result<Live2dMotionService.ActionDetail>> detail(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Pattern(regexp = "[a-f0-9]{32}") String motionId
    ) {
        return noStore(Result.ok(motions.detail(jwt.getSubject(), projectId, motionId)));
    }

    @PutMapping("/motions/{motionId}/draft")
    ResponseEntity<Result<Live2dMotionService.ActionDetail>> draft(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Pattern(regexp = "[a-f0-9]{32}") String motionId,
            @Valid @RequestBody Live2dMotionService.DraftRequest request
    ) {
        return noStore(Result.ok(motions.saveDraft(jwt.getSubject(), projectId, motionId, request)));
    }

    @PostMapping("/motions/{motionId}/duplicate")
    ResponseEntity<Result<Live2dMotionService.ActionDetail>> duplicate(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Pattern(regexp = "[a-f0-9]{32}") String motionId,
            @Valid @RequestBody Live2dMotionService.DuplicateRequest request
    ) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(Result.ok(motions.duplicate(jwt.getSubject(), projectId, motionId, request)));
    }

    @PostMapping("/motions/{motionId}/publish")
    ResponseEntity<Result<Live2dMotionService.VersionDetail>> publish(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Pattern(regexp = "[a-f0-9]{32}") String motionId,
            @Valid @RequestBody Live2dMotionService.PublishRequest request
    ) {
        return noStore(Result.ok(motions.publish(jwt.getSubject(), projectId, motionId, request)));
    }

    @GetMapping("/motions/{motionId}/versions")
    ResponseEntity<Result<Live2dMotionService.Page<Live2dMotionService.MotionVersionView>>> versions(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Pattern(regexp = "[a-f0-9]{32}") String motionId,
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int size
    ) {
        return noStore(Result.ok(motions.versions(jwt.getSubject(), projectId, motionId, page, size)));
    }

    @GetMapping("/motion-versions/{versionId}")
    ResponseEntity<Result<Live2dMotionService.VersionDetail>> version(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Pattern(regexp = "[a-f0-9]{32}") String versionId
    ) {
        return noStore(Result.ok(motions.version(jwt.getSubject(), projectId, versionId)));
    }

    @GetMapping("/models/{modelVersionId}/capabilities")
    ResponseEntity<Result<Live2dMotionService.CapabilitySnapshot>> capabilities(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Positive long modelVersionId, @RequestParam(defaultValue = "false") boolean refresh
    ) {
        return noStore(Result.ok(motions.capabilities(jwt.getSubject(), projectId, modelVersionId, refresh)));
    }

    @PutMapping("/models/{modelVersionId}/capabilities/{capabilityId}")
    ResponseEntity<Result<Live2dMotionService.CapabilitySnapshot>> updateCapability(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Positive long modelVersionId,
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,80}") String capabilityId,
            @Valid @RequestBody Live2dMotionService.MappingRequest request
    ) {
        return noStore(Result.ok(motions.updateCapability(jwt.getSubject(), projectId, modelVersionId,
                capabilityId, request)));
    }

    @GetMapping("/motion-compatibility")
    ResponseEntity<Result<Live2dMotionService.Compatibility>> compatibility(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @RequestParam @Pattern(regexp = "[a-f0-9]{32}") String motionVersionId,
            @RequestParam @Positive long modelVersionId
    ) {
        return noStore(Result.ok(motions.compatibility(jwt.getSubject(), projectId,
                motionVersionId, modelVersionId)));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Result<Void>> invalid() {
        return ResponseEntity.badRequest().body(Result.error(ErrorCode.INVALID_REQUEST));
    }

    private static <T> ResponseEntity<Result<T>> noStore(Result<T> result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
