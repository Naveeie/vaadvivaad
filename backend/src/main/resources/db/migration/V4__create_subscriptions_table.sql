-- V4__create_subscriptions_table.sql
-- Join table: which users are tracking which cases

CREATE TABLE subscriptions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    case_id         UUID    NOT NULL REFERENCES court_cases(id) ON DELETE CASCADE,
    notify_whatsapp BOOLEAN NOT NULL DEFAULT true,
    notify_sms      BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- A user can only subscribe to the same case once
    UNIQUE(user_id, case_id)
);

-- Index for finding all subscriptions for a user
CREATE INDEX idx_subscriptions_user_id ON subscriptions(user_id);

-- Index for finding all subscribers of a case
CREATE INDEX idx_subscriptions_case_id ON subscriptions(case_id);