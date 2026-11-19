package com.xiye.videoasset.auth.service;

import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import java.time.Duration;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class AuthRateLimiter {
    private static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            if count > tonumber(ARGV[2]) then return redis.call('PTTL', KEYS[1]) end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final VerificationProtector protector;

    AuthRateLimiter(StringRedisTemplate redis, VerificationProtector protector) {
        this.redis = redis;
        this.protector = protector;
    }

    void check(String operation, String account, String address, int accountLimit, int ipLimit, Duration window) {
        checkBucket(operation + ":ip:" + address, ipLimit, window);
        checkBucket(operation + ":account:" + account, accountLimit, window);
    }

    private void checkBucket(String bucket, int limit, Duration window) {
        // 计数和首次设置过期时间一起执行，多实例共享同一个窗口。
        String key = "video-asset:auth:rate:" + protector.rateKey(bucket);
        Long remainingMs;
        try {
            remainingMs = redis.execute(LIMIT, List.of(key), Long.toString(window.toMillis()),
                    Integer.toString(limit));
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.AUTH_UNAVAILABLE);
        }
        if (remainingMs == null || remainingMs < 0) {
            throw new BusinessException(ErrorCode.AUTH_UNAVAILABLE);
        }
        if (remainingMs > 0) {
            throw new RateLimited(Math.ceilDiv(remainingMs, 1000));
        }
    }

    public static class RateLimited extends BusinessException {
        private final long retryAfterSeconds;

        public RateLimited(long retryAfterSeconds) {
            super(ErrorCode.RATE_LIMITED);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
