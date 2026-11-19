package com.xiye.videoasset.auth.web;

import com.xiye.videoasset.auth.service.AuthRateLimiter.RateLimited;
import com.xiye.videoasset.auth.service.AuthService;
import com.xiye.videoasset.auth.service.AuthService.Session;
import com.xiye.videoasset.auth.service.AuthService.User;
import com.xiye.videoasset.common.ErrorCode;
import com.xiye.videoasset.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(AuthRoutes.BASE)
class AuthController {
    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping(AuthRoutes.EMAIL_CODES)
    ResponseEntity<Result<SendCodeResponse>> sendCode(
            @Valid @RequestBody EmailCodeRequest payload, HttpServletRequest request
    ) {
        int retryAfter = auth.sendRegistrationCode(payload.email(), request.getRemoteAddr());
        Result<SendCodeResponse> result = new Result<>(
                "OK", "如可处理，验证码将发送至该邮箱，请查收", new SendCodeResponse(retryAfter));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping(AuthRoutes.REGISTER)
    ResponseEntity<Result<User>> register(@Valid @RequestBody RegisterRequest payload, HttpServletRequest request) {
        User user = auth.register(payload.email(), payload.username(), payload.verificationCode(), payload.password(),
                payload.confirmPassword(), request.getRemoteAddr());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(Result.ok(user));
    }

    @PostMapping(AuthRoutes.LOGIN)
    ResponseEntity<Result<Session>> login(@Valid @RequestBody LoginRequest payload, HttpServletRequest request) {
        Session session = auth.login(payload.account(), payload.password(), request.getRemoteAddr());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.ok(session));
    }

    @GetMapping("/me")
    ResponseEntity<Result<User>> me(@AuthenticationPrincipal Jwt jwt) {
        User user = auth.currentUser(jwt.getSubject());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.ok(user));
    }

    @ExceptionHandler(RateLimited.class)
    ResponseEntity<Result<Void>> limited(RateLimited exception) {
        return ResponseEntity.status(429).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(Result.error(ErrorCode.RATE_LIMITED));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Result<Void>> invalidInput() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Result.error(ErrorCode.INVALID_REQUEST));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Result<Void>> storageUnavailable() {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                .body(Result.error(ErrorCode.AUTH_UNAVAILABLE));
    }

    record EmailCodeRequest(@NotBlank @Email @Size(max = 254) String email,
                            @NotBlank @Pattern(regexp = "REGISTER") String purpose) {
        @Override
        public String toString() {
            return "EmailCodeRequest[redacted]";
        }
    }

    record RegisterRequest(@NotBlank @Email @Size(max = 254) String email,
                           @NotBlank @Size(max = 32) String username,
                           @NotBlank @Pattern(regexp = "[0-9]{6}") String verificationCode,
                           @NotBlank @Size(min = 8, max = 72) String password,
                           @NotBlank @Size(min = 8, max = 72) String confirmPassword) {
        @Override
        public String toString() {
            return "RegisterRequest[redacted]";
        }
    }

    record LoginRequest(@NotBlank @Size(max = 254) String account,
                        @NotBlank @Size(max = 72) String password) {
        @Override
        public String toString() {
            return "LoginRequest[redacted]";
        }
    }

    record SendCodeResponse(int retryAfterSeconds) {
    }
}
