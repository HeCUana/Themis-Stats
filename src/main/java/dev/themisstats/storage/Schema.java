package dev.themisstats.storage;

/**
 * The database schema and its versioned migrations.
 *
 * <p>Migrations are applied in order, each inside its own transaction. Before the first migration
 * runs, a consistent backup of the database is created, so a failed migration never leaves a
 * half-updated database behind.</p>
 *
 * <p>All counters are 64 bit integers (SQLite {@code INTEGER} holds 64 bits) and all times are
 * stored as epoch milliseconds, so that a session duration never has to be reconstructed from
 * float seconds.</p>
 *
 * <p>Session identifiers are generated on the server thread (a UUID string) so that the session a
 * metric belongs to is already known when the behaviour happens, without having to wait for a
 * database round trip.</p>
 */
public final class Schema {

    /** Current schema version. Bump this whenever the DDL below changes. */
    public static final int VERSION = 3;

    private Schema() {
    }

    /** The migration steps, indexed by the version they produce. */
    public static String[] migrations() {
        return new String[]{
                // ---- version 1 ------------------------------------------------------
                """
                CREATE TABLE IF NOT EXISTS meta (
                    key   TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS account (
                    uuid         TEXT PRIMARY KEY,
                    current_name TEXT NOT NULL,
                    first_seen   INTEGER NOT NULL,
                    last_seen    INTEGER NOT NULL
                );

                CREATE TABLE IF NOT EXISTS account_name (
                    uuid       TEXT NOT NULL,
                    name       TEXT NOT NULL,
                    first_seen INTEGER NOT NULL,
                    last_seen  INTEGER NOT NULL,
                    PRIMARY KEY (uuid, name)
                );
                CREATE INDEX IF NOT EXISTS idx_account_name_name ON account_name(name);

                CREATE TABLE IF NOT EXISTS session (
                    id                TEXT PRIMARY KEY,
                    boot_id           TEXT    NOT NULL,
                    uuid              TEXT    NOT NULL,
                    identity          TEXT    NOT NULL,
                    start_wall        INTEGER NOT NULL,
                    end_wall          INTEGER,
                    end_reason        TEXT,
                    checkpoint_wall   INTEGER,
                    checkpoint_nano   INTEGER,
                    actual_online_ms  INTEGER NOT NULL DEFAULT 0,
                    collected_ms      INTEGER NOT NULL DEFAULT 0,
                    initial_dimension TEXT,
                    initial_x REAL, initial_y REAL, initial_z REAL,
                    last_dimension    TEXT,
                    last_x REAL, last_y REAL, last_z REAL,
                    owner_uuid        TEXT,
                    owner_name        TEXT,
                    purpose           TEXT,
                    note              TEXT,
                    complete          INTEGER NOT NULL DEFAULT 1,
                    gap_note          TEXT
                );
                CREATE INDEX IF NOT EXISTS idx_session_uuid ON session(uuid, identity);
                CREATE INDEX IF NOT EXISTS idx_session_boot ON session(boot_id);
                CREATE INDEX IF NOT EXISTS idx_session_open ON session(end_wall);

                CREATE TABLE IF NOT EXISTS session_pause (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT    NOT NULL,
                    start_wall INTEGER NOT NULL,
                    end_wall   INTEGER,
                    reason     TEXT
                );
                CREATE INDEX IF NOT EXISTS idx_session_pause_session ON session_pause(session_id);

                CREATE TABLE IF NOT EXISTS session_metric (
                    session_id TEXT    NOT NULL,
                    metric     TEXT    NOT NULL,
                    category   TEXT    NOT NULL DEFAULT '',
                    dimension  TEXT    NOT NULL DEFAULT '',
                    game_mode  TEXT    NOT NULL DEFAULT '',
                    value      INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (session_id, metric, category, dimension, game_mode)
                );

                CREATE TABLE IF NOT EXISTS total (
                    uuid      TEXT    NOT NULL,
                    identity  TEXT    NOT NULL,
                    metric    TEXT    NOT NULL,
                    category  TEXT    NOT NULL DEFAULT '',
                    dimension TEXT    NOT NULL DEFAULT '',
                    game_mode TEXT    NOT NULL DEFAULT '',
                    value     INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (uuid, identity, metric, category, dimension, game_mode)
                );
                CREATE INDEX IF NOT EXISTS idx_total_rank
                    ON total(metric, identity, game_mode, dimension, value DESC);

                CREATE TABLE IF NOT EXISTS adjustment (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid         TEXT    NOT NULL,
                    identity     TEXT    NOT NULL,
                    metric       TEXT    NOT NULL,
                    category     TEXT    NOT NULL DEFAULT '',
                    dimension    TEXT    NOT NULL DEFAULT '',
                    game_mode    TEXT    NOT NULL DEFAULT '',
                    delta        INTEGER NOT NULL,
                    reason       TEXT    NOT NULL,
                    operator     TEXT    NOT NULL,
                    created_wall INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_adjustment_scope
                    ON adjustment(uuid, identity, metric);

                CREATE TABLE IF NOT EXISTS exclusion (
                    uuid         TEXT    NOT NULL,
                    identity     TEXT    NOT NULL,
                    excluded     INTEGER NOT NULL DEFAULT 1,
                    reason       TEXT    NOT NULL,
                    operator     TEXT    NOT NULL,
                    created_wall INTEGER NOT NULL,
                    PRIMARY KEY (uuid, identity)
                );

                CREATE TABLE IF NOT EXISTS bot_profile (
                    uuid         TEXT NOT NULL,
                    identity     TEXT    NOT NULL,
                    owner_uuid   TEXT,
                    owner_name   TEXT,
                    purpose      TEXT,
                    note         TEXT,
                    updated_wall INTEGER NOT NULL,
                    PRIMARY KEY (uuid, identity)
                );
                CREATE INDEX IF NOT EXISTS idx_bot_profile_owner ON bot_profile(owner_uuid);

                CREATE TABLE IF NOT EXISTS bot_tag (
                    uuid     TEXT NOT NULL,
                    identity TEXT NOT NULL,
                    tag      TEXT NOT NULL,
                    PRIMARY KEY (uuid, identity, tag)
                );

                CREATE TABLE IF NOT EXISTS bot_owner_history (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid         TEXT    NOT NULL,
                    identity     TEXT    NOT NULL,
                    owner_uuid   TEXT,
                    owner_name   TEXT,
                    changed_wall INTEGER NOT NULL,
                    operator     TEXT    NOT NULL
                );

                CREATE TABLE IF NOT EXISTS display_pref (
                    uuid           TEXT PRIMARY KEY,
                    mode           TEXT    NOT NULL,
                    metric         TEXT,
                    identity_group TEXT,
                    updated_wall   INTEGER NOT NULL
                );

                CREATE TABLE IF NOT EXISTS audit (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    action       TEXT    NOT NULL,
                    target       TEXT,
                    detail       TEXT,
                    operator     TEXT    NOT NULL,
                    created_wall INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_audit_created ON audit(created_wall DESC);

                CREATE TABLE IF NOT EXISTS collection_window (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    start_wall INTEGER NOT NULL,
                    end_wall   INTEGER,
                    reason     TEXT
                );

                CREATE TABLE IF NOT EXISTS applied_batch (
                    batch_id     TEXT PRIMARY KEY,
                    applied_wall INTEGER NOT NULL,
                    increments  INTEGER NOT NULL
                );
                """,
                // ---- version 2 ------------------------------------------------------
                // The account name lookup is case insensitive, so that a bot that is called
                // "Bot_A" can also be found under the name the server stored for it.
                """
                CREATE TABLE account_name_new (
                    uuid       TEXT NOT NULL,
                    name       TEXT NOT NULL COLLATE NOCASE,
                    first_seen INTEGER NOT NULL,
                    last_seen  INTEGER NOT NULL,
                    PRIMARY KEY (uuid, name)
                );
                INSERT OR REPLACE INTO account_name_new(uuid, name, first_seen, last_seen)
                    SELECT uuid, name, first_seen, last_seen FROM account_name;
                DROP TABLE account_name;
                ALTER TABLE account_name_new RENAME TO account_name;
                CREATE INDEX IF NOT EXISTS idx_account_name_name ON account_name(name);
                """,
                // ---- version 3 ------------------------------------------------------
                // A bot like object has one profile per identity, so the primary key has to
                // cover the identity as well.
                """
                CREATE TABLE bot_profile_new (
                    uuid         TEXT NOT NULL,
                    identity     TEXT    NOT NULL,
                    owner_uuid   TEXT,
                    owner_name   TEXT,
                    purpose      TEXT,
                    note         TEXT,
                    updated_wall INTEGER NOT NULL,
                    PRIMARY KEY (uuid, identity)
                );
                INSERT OR REPLACE INTO bot_profile_new(uuid, identity, owner_uuid, owner_name, purpose, note, updated_wall)
                    SELECT uuid, identity, owner_uuid, owner_name, purpose, note, updated_wall FROM bot_profile;
                DROP TABLE bot_profile;
                ALTER TABLE bot_profile_new RENAME TO bot_profile;
                CREATE INDEX IF NOT EXISTS idx_bot_profile_owner ON bot_profile(owner_uuid);
                """
        };
    }
}
