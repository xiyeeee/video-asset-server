package com.xiye.videoasset.auth.service;

import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class VerificationProtector {
    private final byte[] key;

    VerificationProtector(@Value("${app.auth.code-hmac-key:}") String configuredKey) {
        key = Base64.getDecoder().decode(configuredKey);
        if (key.length > 0 && key.length < 32) {
            throw new IllegalArgumentException("AUTH_CODE_HMAC_KEY must contain at least 32 decoded bytes");
        }
    }

    byte[] codeMac(String email, String challengeId, String code) {
        return sign("verification\nREGISTER\n" + email + "\n" + challengeId + "\n" + code);
    }

    String rateKey(String value) {
        return HexFormat.of().formatHex(sign("rate-limit\n" + value));
    }

    private byte[] sign(String value) {
        if (key.length == 0) {
            throw new BusinessException(ErrorCode.AUTH_UNAVAILABLE);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not calculate authentication HMAC", exception);
        }
    }
}
