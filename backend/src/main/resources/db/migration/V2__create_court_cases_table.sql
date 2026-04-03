-- V2__create_court_cases_table.sql
-- Store court case information scraped from eCourts

CREATE TABLE court_cases (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cnr_number          VARCHAR(50)  NOT NULL UNIQUE,
    case_type           VARCHAR(100),
    filing_number       VARCHAR(100),
    filing_date         DATE,
    registration_number VARCHAR(100),
    registration_date   DATE,
    status              VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    petitioner          TEXT,
    respondent          TEXT,
    court_name          VARCHAR(255),
    judge_name          VARCHAR(255),
    last_scraped_at     TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index on CNR for lookups (already UNIQUE, but explicit for clarity)
CREATE INDEX idx_court_cases_cnr ON court_cases(cnr_number);

-- Index on status for filtering active cases
CREATE INDEX idx_court_cases_status ON court_cases(status);