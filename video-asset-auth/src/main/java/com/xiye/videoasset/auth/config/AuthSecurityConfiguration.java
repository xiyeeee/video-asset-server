package com.xiye.videoasset.auth.config;

import com.xiye.videoasset.auth.web.AuthRoutes;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.util.Assert;

import static org.springframework.http.HttpMethod.POST;

@Configuration(proxyBeanMethods = false)
class AuthSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain publicAuthSecurity(HttpSecurity http) throws Exception {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        return http.securityMatcher(new OrRequestMatcher(
                        paths.matcher(POST, AuthRoutes.BASE + AuthRoutes.EMAIL_CODES),
                        paths.matcher(POST, AuthRoutes.BASE + AuthRoutes.REGISTER),
                        paths.matcher(POST, AuthRoutes.BASE + AuthRoutes.LOGIN)))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "JWT_PRIVATE_KEY_LOCATION")
    JwtEncoder jwtEncoder(
            @Value("${JWT_PRIVATE_KEY_LOCATION}") Resource privateKey,
            @Value("${app.jwt.public-key-location}") Resource publicKey
    ) throws IOException {
        try (InputStream privateInput = privateKey.getInputStream();
             InputStream publicInput = publicKey.getInputStream()) {
            RSAPrivateKey privateRsaKey = RsaKeyConverters.pkcs8().convert(privateInput);
            RSAPublicKey publicRsaKey = RsaKeyConverters.x509().convert(publicInput);
            Assert.state(publicRsaKey.getModulus().equals(privateRsaKey.getModulus()), "JWT RSA key pair must match");
            RSAKey signingKey = new RSAKey.Builder(publicRsaKey).privateKey(privateRsaKey).build();
            return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
        }
    }
}
