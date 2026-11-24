package com.xiye.videoasset.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiye.videoasset.common.ApiPaths;
import com.xiye.videoasset.common.ErrorCode;
import com.xiye.videoasset.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
public class ResourceServerConfiguration {
    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, Environment environment, ObjectMapper mapper) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, failure) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            writeError(response, mapper, ErrorCode.UNAUTHENTICATED);
        };
        AccessDeniedHandler forbidden = (request, response, failure) ->
                writeError(response, mapper, ErrorCode.FORBIDDEN);

        // 资源服务从请求头读取 Bearer Token，不依赖浏览器会话 Cookie。
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(HttpMethod.GET, ApiPaths.HEALTH, ApiPaths.HEALTH + "/**").permitAll();
                    // 模型依赖由相对 URL 加载，资源入口验证短期、单模型票据。
                    requests.requestMatchers(HttpMethod.GET, "/projects/*/live2d/models/*/files/**").permitAll();
                    if (environment.acceptsProfiles(Profiles.of("dev & !prod"))) {
                        requests.requestMatchers(HttpMethod.GET, ApiPaths.DEV_PROBE).permitAll();
                    }
                    requests.anyRequest().authenticated();
                })
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .oauth2ResourceServer(oauth -> oauth
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden)
                        .bearerTokenResolver(ResourceServerConfiguration::resolveBearer)
                        .jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${app.jwt.public-key-location}") Resource publicKey,
            @Value("${app.jwt.issuer}") String issuer,
            @Value("${app.jwt.audience}") String audience
    ) throws IOException {
        Assert.hasText(issuer, "JWT issuer is required");
        Assert.hasText(audience, "JWT audience is required");
        try (InputStream publicKeyInput = publicKey.getInputStream()) {
            RSAPublicKey key = RsaKeyConverters.x509().convert(publicKeyInput);
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key).build();
            JwtClaimValidator<List<String>> audienceValidator = new JwtClaimValidator<>(JwtClaimNames.AUD,
                    claims -> claims != null && claims.contains(audience));
            // 默认时间校验允许 exp 缺省，资源服务另要求令牌必须携带过期时间。
            JwtClaimValidator<Object> expiryValidator =
                    new JwtClaimValidator<>(JwtClaimNames.EXP, expiry -> expiry != null);
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(issuer), audienceValidator, expiryValidator));
            return decoder;
        }
    }

    private static String resolveBearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = header.substring(7).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        String path = request.getRequestURI();
        if (path == null || !path.contains("/live2d/tasks/") || !path.endsWith("/events")) {
            return null;
        }
        String protocols = request.getHeader("Sec-WebSocket-Protocol");
        if (protocols == null) {
            return null;
        }
        for (String part : protocols.split(",")) {
            String token = part.trim();
            if (!token.isEmpty() && !token.equalsIgnoreCase("video-asset.bearer")) {
                return token;
            }
        }
        return null;
    }

    private void writeError(HttpServletResponse response, ObjectMapper mapper, ErrorCode code) throws IOException {
        response.setStatus(code.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), Result.error(code));
    }
}
