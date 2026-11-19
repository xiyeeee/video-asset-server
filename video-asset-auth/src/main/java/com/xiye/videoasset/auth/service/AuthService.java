package com.xiye.videoasset.auth.service;

import com.xiye.videoasset.auth.persistence.AccountRepository;
import com.xiye.videoasset.auth.persistence.AccountRepository.Account;
import com.xiye.videoasset.auth.persistence.EmailChallengeRepository;
import com.xiye.videoasset.auth.persistence.EmailChallengeRepository.Challenge;
import com.xiye.videoasset.auth.service.AuthRateLimiter.RateLimited;
import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern ASCII = Pattern.compile("\\p{ASCII}+");
    private static final int CODE_TTL_SECONDS = 300;
    private static final int RESEND_SECONDS = 60;
    private static final int MAX_CODE_ATTEMPTS = 5;
    private static final int PASSWORD_MAX_BYTES = 72;
    private static final Duration TOKEN_TTL = Duration.ofHours(2);

    private final AccountRepository accounts;
    private final EmailChallengeRepository challenges;
    private final VerificationProtector protector;
    private final VerificationMailSender mail;
    private final AuthRateLimiter rateLimiter;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transaction;
    private final ObjectProvider<JwtEncoder> jwtEncoder;
    private final String issuer;
    private final String audience;
    private final String fallbackPasswordHash;

    public AuthService(
            AccountRepository accounts, EmailChallengeRepository challenges,
            VerificationProtector protector, VerificationMailSender mail, AuthRateLimiter rateLimiter,
            PasswordEncoder passwords, TransactionTemplate transaction, ObjectProvider<JwtEncoder> jwtEncoder,
            @Value("${app.jwt.issuer}") String issuer, @Value("${app.jwt.audience}") String audience
    ) {
        this.accounts = accounts;
        this.challenges = challenges;
        this.protector = protector;
        this.mail = mail;
        this.rateLimiter = rateLimiter;
        this.passwords = passwords;
        this.transaction = transaction;
        this.jwtEncoder = jwtEncoder;
        this.issuer = issuer;
        this.audience = audience;
        this.fallbackPasswordHash = passwords.encode(UUID.randomUUID().toString());
    }

    public int sendRegistrationCode(String address, String requestAddress) {
        String email = normalizedEmail(address);
        rateLimiter.check("mail", email, requestAddress, 5, 20, Duration.ofHours(1));
        mail.requireConfiguration();
        Delivery delivery = transaction.execute(status -> reserveCode(email));
        if (delivery == null) {
            return RESEND_SECONDS;
        }

        // 等待 SMTP 时不持有数据库行锁，只允许当前挑战确认投递结果。
        boolean sent = mail.send(email, delivery.code());
        boolean deliveryRecorded = challenges.finishDelivery(email, delivery.id(), sent,
                Instant.now().plusSeconds(CODE_TTL_SECONDS));
        if (!sent || !deliveryRecorded) {
            throw new BusinessException(ErrorCode.MAIL_UNAVAILABLE);
        }
        return RESEND_SECONDS;
    }

    private Delivery reserveCode(String email) {
        challenges.ensureSlot(email);
        Challenge previous = challenges.lock(email).orElseThrow();
        Instant now = Instant.now();
        if (previous.cooldownUntil() != null && previous.cooldownUntil().isAfter(now)) {
            long remaining = Duration.between(now, previous.cooldownUntil()).toMillis();
            throw new RateLimited(Math.max(1, Math.ceilDiv(remaining, 1000)));
        }

        String challengeId = UUID.randomUUID().toString();
        String code;
        // 即使随机数碰巧重复，重发后也不能用上一封邮件的数字通过验证。
        do {
            code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        } while (previous.codeMac() != null
                && MessageDigest.isEqual(previous.codeMac(), protector.codeMac(email, previous.id(), code)));
        challenges.reserve(email, challengeId, protector.codeMac(email, challengeId, code),
                now.plusSeconds(RESEND_SECONDS));
        if (accounts.emailExists(email)) {
            // 已注册邮箱沿用相同的重发冷却和受理响应。
            challenges.finishDelivery(email, challengeId, false, now);
            return null;
        }
        return new Delivery(challengeId, code);
    }

    public User register(
            String address, String requestedUsername, String code, String password, String confirmPassword,
            String requestAddress
    ) {
        String email = normalizedEmail(address);
        String username = normalize(requestedUsername);
        if (!username.matches("[a-z0-9_]{3,32}") || !validPasswordSize(password)
                || !password.equals(confirmPassword)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        rateLimiter.check("register", email, requestAddress, 10, 30, Duration.ofMinutes(15));
        String passwordHash = passwords.encode(password);

        try {
            Account created = transaction.execute(status -> registerWithChallenge(email, username, code, passwordHash));
            if (created == null) {
                throw new BusinessException(ErrorCode.VERIFICATION_INVALID);
            }
            return user(created);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.ACCOUNT_CONFLICT);
        }
    }

    private Account registerWithChallenge(String email, String username, String code, String passwordHash) {
        Challenge challenge = challenges.lock(email).orElse(null);
        Instant now = Instant.now();
        if (challenge == null || !challenge.usableAt(now, MAX_CODE_ATTEMPTS)) {
            return null;
        }

        byte[] suppliedMac = protector.codeMac(email, challenge.id(), code);
        if (!MessageDigest.isEqual(challenge.codeMac(), suppliedMac)) {
            // 在事务外返回验证码错误，确保本次错误计数已经提交。
            challenges.recordFailure(email);
            return null;
        }

        Account account = accounts.insert(email, username, passwordHash, now);
        challenges.consume(email);
        return account;
    }

    public Session login(String accountName, String password, String requestAddress) {
        String normalizedAccount = normalize(accountName);
        if (!validPasswordSize(password)) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        rateLimiter.check("login", normalizedAccount, requestAddress, 10, 50, Duration.ofMinutes(15));
        Account account = accounts.byLogin(normalizedAccount).orElse(null);
        String expectedHash = account == null ? fallbackPasswordHash : account.passwordHash();
        if (!passwords.matches(password, expectedHash) || account == null || !account.active()) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        JwtEncoder encoder = jwtEncoder.getIfAvailable();
        if (encoder == null) {
            throw new BusinessException(ErrorCode.AUTH_UNAVAILABLE);
        }

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(Long.toString(account.id()))
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(TOKEN_TTL))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new Session(token, "Bearer", TOKEN_TTL.toSeconds(), user(account));
    }

    public User currentUser(String subject) {
        try {
            return accounts.byId(Long.parseLong(subject))
                    .filter(Account::active)
                    .map(AuthService::user)
                    .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
    }

    private static boolean validPasswordSize(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= PASSWORD_MAX_BYTES;
    }

    private static String normalizedEmail(String address) {
        String email = normalize(address);
        if (!ASCII.matcher(email).matches()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return email;
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static User user(Account account) {
        return new User(Long.toString(account.id()), account.username(), account.email(), account.displayName());
    }

    private record Delivery(String id, String code) {
        @Override
        public String toString() {
            return "Delivery[redacted]";
        }
    }

    public record User(String id, String username, String email, String displayName) {
        @Override
        public String toString() {
            return "User[id=" + id + "]";
        }
    }

    public record Session(String accessToken, String tokenType, long expiresIn, User user) {
        @Override
        public String toString() {
            return "Session[user=" + user + ", expiresIn=" + expiresIn + "]";
        }
    }
}
