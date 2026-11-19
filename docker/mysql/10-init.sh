# Sourced by the official MySQL entrypoint during first boot.
escape_sql_literal() {
    printf '%s' "$1" | sed "s/'/''/g"
}

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
