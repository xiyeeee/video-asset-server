package com.xiye.videoasset.auth.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
    private static final String ACCOUNT_COLUMNS =
            "SELECT id, username, email, display_name, password_hash, status FROM sys_user WHERE deleted = 0";
    private static final RowMapper<Account> ACCOUNT = (result, row) -> new Account(
            result.getLong("id"), result.getString("username"), result.getString("email"),
            result.getString("display_name"), result.getString("password_hash"), result.getString("status"));

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean emailExists(String email) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM sys_user WHERE email = :email)")
                .param("email", email).query(Boolean.class).single();
    }

    public Optional<Account> byLogin(String account) {
        String where = account.contains("@") ? " AND email = :account" : " AND username = :account";
        return jdbc.sql(ACCOUNT_COLUMNS + where).param("account", account).query(ACCOUNT).optional();
    }

    public Optional<Account> byId(long id) {
        return jdbc.sql(ACCOUNT_COLUMNS + " AND id = :id").param("id", id).query(ACCOUNT).optional();
    }

    public Account insert(String email, String username, String passwordHash, Instant verifiedAt) {
        jdbc.sql("""
                INSERT INTO sys_user(username, email, email_verified_at, display_name, password_hash)
                VALUES (:username, :email, :verifiedAt, :username, :hash)
                """)
                .param("username", username).param("email", email).param("verifiedAt", Timestamp.from(verifiedAt))
                .param("hash", passwordHash).update();
        return byLogin(email).orElseThrow();
    }

    public record Account(long id, String username, String email, String displayName, String passwordHash,
                          String status) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }
}
