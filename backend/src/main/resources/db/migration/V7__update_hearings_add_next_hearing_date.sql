-- V7__update_hearings_add_next_hearing_date.sql

/*
 * WHY rename detail to notes instead of dropping and adding?
 * RENAME preserves existing data in that column (seed data from V6).
 * DROP + ADD would null out all existing hearing detail text.
 *
 * WHY not edit V3 directly?
 * Flyway tracks which migrations have run via the flyway_schema_history
 * table. Editing an already-executed migration causes a checksum mismatch
 * and Flyway refuses to start. Always add new migrations, never edit old ones.
 */

ALTER TABLE hearings
    RENAME COLUMN detail TO notes;

ALTER TABLE hearings
    ADD COLUMN next_hearing_date DATE;

-- Index for scheduler queries that find hearings by next_hearing_date
CREATE INDEX idx_hearings_next_date ON hearings(next_hearing_date);