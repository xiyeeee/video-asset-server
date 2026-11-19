package com.xiye.videoasset.auth.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EmailChallengeRepository {
    private static final String SLOT = " WHERE email = :email AND purpose = 'REGISTER'";
    private final JdbcClient jdbc;

    public EmailChallengeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureSlot(String email) {
        jdbc.sql("""
                INSERT INTO auth_email_challenge(email, purpose) VALUES (:email, 'REGISTER')
                ON DUPLICATE KEY UPDATE email = email
                """).param("email", email).update();
    }

    public Optional<Challenge> lock(String email) {
        return jdbc.sql("""
                SELECT challenge_id, code_mac, status, expires_at, cooldown_until, failed_attempts
                FROM auth_email_challenge
                """ + SLOT + " FOR UPDATE")
                .param("email", email).query((row, index) -> new Challenge(row.getString("challenge_id"),
                        row.getBytes("code_mac"), row.getString("status"), instant(row, "expires_at"),
                        instant(row, "cooldown_until"), row.getInt("failed_attempts"))).optional();
    }

    public void reserve(String email, String id, byte[] codeMac, Instant cooldown) {
        jdbc.sql("""
                UPDATE auth_email_challenge
                SET challenge_id = :id, code_mac = :mac, status = 'PENDING', expires_at = NULL,
                    cooldown_until = :cooldown, failed_attempts = 0
                """ + SLOT)
                .param("email", email).param("id", id).param("mac", codeMac)
                .param("cooldown", Timestamp.from(cooldown)).update();
    }

    public boolean finishDelivery(String email, String id, boolean sent, Instant expiry) {
        return jdbc.sql("""
                UPDATE auth_email_challenge SET status = :status, expires_at = :expiresAt
                """ + SLOT + " AND challenge_id = :id AND status = 'PENDING'")
                .param("email", email).param("id", id).param("status", sent ? "SENT" : "FAILED")
                .param("expiresAt", Timestamp.from(expiry)).update() == 1;
    }

    public void recordFailure(String email) {
        jdbc.sql("UPDATE auth_email_challenge SET failed_attempts = failed_attempts + 1" + SLOT)
                .param("email", email).update();
    }

    public void consume(String email) {
        jdbc.sql("UPDATE auth_email_challenge SET status = 'USED', code_mac = NULL" + SLOT)
                .param("email", email).update();
    }

    private static Instant instant(ResultSet row, String name) throws SQLException {
        Timestamp value = row.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    public record Challenge(String id, byte[] codeMac, String status, Instant expiresAt, Instant cooldownUntil,
                            int failedAttempts) {
        public boolean usableAt(Instant now, int maxAttempts) {
            return "SENT".equals(status) && expiresAt != null && expiresAt.isAfter(now)
                    && failedAttempts < maxAttempts;
        }
    }
}
