-- BuildReplay Schema Version 1 (Ausgangsstand).
--
-- {prefix} wird durch database.table-prefix ersetzt. Alle Zeitpunkte sind DATETIME(3) in UTC, Enums als VARCHAR.
-- DDL ist in MariaDB nicht transaktional; jede Anweisung ist deshalb für sich wiederholbar (IF NOT EXISTS), damit
-- eine abgebrochene Migration beim nächsten Versuch einfach fortgesetzt wird. Die Tabelle {prefix}schema_version legt
-- der Migrator selbst an. Alle Tabellen nutzen utf8mb4_bin: natürliche Schlüssel (Session-IDs, Replay-Namen) werden
-- exakt verglichen und Fremdschlüsselspalten haben überall dieselbe Kollation.

CREATE TABLE IF NOT EXISTS {prefix}players (
    player_uuid CHAR(36)    NOT NULL,
    last_name   VARCHAR(16) NULL,
    first_seen  DATETIME(3) NOT NULL,
    last_seen   DATETIME(3) NOT NULL,
    PRIMARY KEY (player_uuid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

CREATE TABLE IF NOT EXISTS {prefix}sessions (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    session_key      VARCHAR(64) NOT NULL,
    cloud_session_id CHAR(36)    NULL,
    player_uuid      CHAR(36)    NOT NULL,
    source           VARCHAR(16) NOT NULL,
    status           VARCHAR(16) NOT NULL,
    login_time       DATETIME(3) NULL,
    logout_time      DATETIME(3) NULL,
    duration_seconds BIGINT      NULL,
    merged_into      BIGINT      NULL,
    created_at       DATETIME(3) NOT NULL,
    updated_at       DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_key (session_key),
    UNIQUE KEY uk_cloud_session_id (cloud_session_id),
    KEY idx_player_login (player_uuid, login_time),
    KEY idx_status_updated (status, updated_at),
    KEY idx_merged_into (merged_into),
    CONSTRAINT fk_{prefix}sessions_player FOREIGN KEY (player_uuid) REFERENCES {prefix}players (player_uuid),
    CONSTRAINT fk_{prefix}sessions_merged FOREIGN KEY (merged_into) REFERENCES {prefix}sessions (id)
        ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

CREATE TABLE IF NOT EXISTS {prefix}server_visits (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    visit_key   VARCHAR(128) NOT NULL,
    session_id  BIGINT       NOT NULL,
    player_uuid CHAR(36)     NOT NULL,
    server_name VARCHAR(64)  NOT NULL,
    instance_id VARCHAR(64)  NOT NULL,
    joined_at   DATETIME(3)  NOT NULL,
    left_at     DATETIME(3)  NULL,
    end_reason  VARCHAR(16)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_visit_key (visit_key),
    KEY idx_session_joined (session_id, joined_at),
    KEY idx_server_left (server_name, left_at),
    CONSTRAINT fk_{prefix}visits_session FOREIGN KEY (session_id) REFERENCES {prefix}sessions (id)
        ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

CREATE TABLE IF NOT EXISTS {prefix}recordings (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    raw_name         VARCHAR(40)  NOT NULL,
    session_id       BIGINT       NULL,
    player_uuid      CHAR(36)     NOT NULL,
    player_name      VARCHAR(16)  NOT NULL,
    server_name      VARCHAR(64)  NOT NULL,
    instance_id      VARCHAR(64)  NOT NULL,
    world_name       VARCHAR(128) NULL,
    started_at       DATETIME(3)  NOT NULL,
    ended_at         DATETIME(3)  NULL,
    status           VARCHAR(16)  NOT NULL,
    relevant_actions INT          NOT NULL DEFAULT 0,
    error            VARCHAR(500) NULL,
    updated_at       DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_raw_name (raw_name),
    KEY idx_session (session_id),
    KEY idx_server_status (server_name, status),
    CONSTRAINT fk_{prefix}recordings_session FOREIGN KEY (session_id) REFERENCES {prefix}sessions (id)
        ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

CREATE TABLE IF NOT EXISTS {prefix}segments (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    replay_name      VARCHAR(40)  NOT NULL,
    session_id       BIGINT       NOT NULL,
    recording_id     BIGINT       NULL,
    player_uuid      CHAR(36)     NOT NULL,
    player_name      VARCHAR(16)  NOT NULL,
    server_name      VARCHAR(64)  NOT NULL,
    world_name       VARCHAR(128) NULL,
    storage_backend  VARCHAR(16)  NOT NULL,
    storage_ref      VARCHAR(255) NULL,
    started_at       DATETIME(3)  NOT NULL,
    ended_at         DATETIME(3)  NOT NULL,
    first_action_at  DATETIME(3)  NOT NULL,
    last_action_at   DATETIME(3)  NOT NULL,
    duration_ticks   INT          NOT NULL,
    pre_roll_ms      INT          NOT NULL,
    post_roll_ms     INT          NOT NULL,
    relevant_actions INT          NOT NULL,
    action_summary   TEXT         NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    size_bytes       BIGINT       NULL,
    note             VARCHAR(500) NULL,
    created_at       DATETIME(3)  NOT NULL,
    updated_at       DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_replay_name (replay_name),
    KEY idx_session_started (session_id, started_at),
    KEY idx_player_started (player_uuid, started_at),
    KEY idx_server_status_ended (server_name, status, ended_at),
    KEY idx_recording (recording_id),
    CONSTRAINT fk_{prefix}segments_session FOREIGN KEY (session_id) REFERENCES {prefix}sessions (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_{prefix}segments_recording FOREIGN KEY (recording_id) REFERENCES {prefix}recordings (id)
        ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;
