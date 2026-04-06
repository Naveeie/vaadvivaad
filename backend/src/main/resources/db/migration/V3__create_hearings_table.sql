-- V3__create_hearings_table.sql
-- Store individual hearing records for each court case

CREATE TABLE hearings (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id             UUID         NOT NULL REFERENCES court_cases(id) ON DELETE CASCADE,
    hearing_date        DATE         NOT NULL,
    purpose             VARCHAR(500),
    detail              TEXT,
    ai_summary_hindi    TEXT,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index on case_id for fetching hearings by case
CREATE INDEX idx_hearings_case_id ON hearings(case_id);

-- Index on hearing_date for finding upcoming hearings
CREATE INDEX idx_hearings_date ON hearings(hearing_date);