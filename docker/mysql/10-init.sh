# Sourced by the official MySQL entrypoint during first boot.
escape_sql_literal() {
    printf '%s' "$1" | sed "s/'/''/g"
}

if [ "${MYSQL_DEV_SCHEMA_ACCESS:-false}" = true ]; then
    : "${MYSQL_MEDIA_PASSWORD:?Set MYSQL_MEDIA_PASSWORD}"
    : "${MYSQL_AI_PASSWORD:?Set MYSQL_AI_PASSWORD}"
    for runtime_user in "$MYSQL_MEDIA_USERNAME" "$MYSQL_AI_USERNAME"; do
        case "$runtime_user" in
            ''|root|video_asset_auth|video_asset_migration|nacos_config)
                echo "Choose separate MySQL runtime usernames for Media and AI." >&2
                return 1
                ;;
        esac
    done
    if [ "$MYSQL_MEDIA_USERNAME" = "$MYSQL_AI_USERNAME" ]; then
        echo "Choose separate MySQL runtime usernames for Media and AI." >&2
        return 1
    fi
fi

docker_process_sql <<SQL
SET SESSION sql_mode = 'NO_BACKSLASH_ESCAPES';
CREATE DATABASE video_asset CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE nacos_config CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'video_asset_auth'@'%' IDENTIFIED BY '$(escape_sql_literal "$MYSQL_AUTH_PASSWORD")';
CREATE USER 'video_asset_migration'@'%' IDENTIFIED BY '$(escape_sql_literal "$MYSQL_MIGRATION_PASSWORD")';
CREATE USER 'nacos_config'@'%' IDENTIFIED BY '$(escape_sql_literal "$MYSQL_NACOS_PASSWORD")';

GRANT SELECT ON video_asset.* TO 'video_asset_auth'@'%';
GRANT ALL PRIVILEGES ON video_asset.* TO 'video_asset_migration'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON nacos_config.* TO 'nacos_config'@'%';
SQL

docker_process_sql --database=nacos_config < /opt/nacos-schema.sql

if [ "${MYSQL_DEV_SCHEMA_ACCESS:-false}" = true ]; then
    # 数据库级授权中的下划线需要转义，限定在 video_asset。
    docker_process_sql <<SQL
SET SESSION sql_mode = 'NO_BACKSLASH_ESCAPES';
CREATE USER '$(escape_sql_literal "$MYSQL_MEDIA_USERNAME")'@'%' IDENTIFIED BY '$(escape_sql_literal "$MYSQL_MEDIA_PASSWORD")';
CREATE USER '$(escape_sql_literal "$MYSQL_AI_USERNAME")'@'%' IDENTIFIED BY '$(escape_sql_literal "$MYSQL_AI_PASSWORD")';
GRANT SELECT, INSERT, UPDATE ON \`video\\_asset\`.* TO '$(escape_sql_literal "$MYSQL_MEDIA_USERNAME")'@'%';
GRANT SELECT, INSERT, UPDATE ON \`video\\_asset\`.* TO '$(escape_sql_literal "$MYSQL_AI_USERNAME")'@'%';
SQL
fi
