package com.xiye.videoasset.ai.live2d;

import com.xiye.videoasset.common.ErrorCode;
import com.xiye.videoasset.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
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
@RequestMapping("/projects/{projectId}/live2d/combo-motions")
class Live2dComboController {
    private final Live2dComboService combos;

    Live2dComboController(Live2dComboService combos) {
        this.combos = combos;
    }

    @GetMapping("/catalog")
    ResponseEntity<Result<Live2dComboService.Catalog>> catalog(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @RequestParam @Positive long modelVersionId, @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "50") @Positive @Max(100) int size
    ) {
        return noStore(Result.ok(combos.catalog(jwt.getSubject(), projectId, modelVersionId, page, size)));
    }

    @PostMapping("/compile")
    ResponseEntity<Result<Live2dComboService.CompileView>> compile(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @Valid @RequestBody Live2dComboService.CompileRequest request
    ) {
        return noStore(Result.ok(combos.compile(jwt.getSubject(), projectId, request)));
    }

    @GetMapping
    ResponseEntity<Result<Live2dComboService.Page<Live2dComboMapper.ComboSummary>>> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int size
    ) {
        return noStore(Result.ok(combos.list(jwt.getSubject(), projectId, page, size)));
    }

    @PostMapping
    ResponseEntity<Result<Live2dComboService.ComboDetail>> create(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @Valid @RequestBody Live2dComboService.SaveRequestBody request
    ) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(Result.ok(combos.create(jwt.getSubject(), projectId, request)));
    }

    @PutMapping("/{comboId}")
    ResponseEntity<Result<Live2dComboService.ComboDetail>> update(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Pattern(regexp = "[a-f0-9]{32}") String comboId,
            @Valid @RequestBody Live2dComboService.SaveRequestBody request
    ) {
        return noStore(Result.ok(combos.update(jwt.getSubject(), projectId, comboId, request)));
    }

    @GetMapping("/{comboId}")
    ResponseEntity<Result<Live2dComboService.ComboDetail>> detail(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Pattern(regexp = "[a-f0-9]{32}") String comboId
    ) {
        return noStore(Result.ok(combos.detail(jwt.getSubject(), projectId, comboId)));
    }

    @PostMapping("/save-requests/{requestId}/retry")
    ResponseEntity<Result<Live2dComboService.ComboDetail>> retry(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long projectId,
            @PathVariable @Pattern(regexp = "[a-f0-9]{32}") String requestId
    ) {
        return noStore(Result.ok(combos.retrySave(jwt.getSubject(), projectId, requestId)));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Result<Void>> invalid() {
        return ResponseEntity.badRequest().body(Result.error(ErrorCode.INVALID_REQUEST));
    }

    private static <T> ResponseEntity<Result<T>> noStore(Result<T> result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
