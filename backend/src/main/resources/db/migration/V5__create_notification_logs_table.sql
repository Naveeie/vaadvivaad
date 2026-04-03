-- V5__create_notification_logs_table.sql
-- Track every notification sent (for audit and retry)

CREATE TABLE notification_logs (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hearing_id    UUID         NOT NULL REFERENCES hearings(id) ON DELETE CASCADE,
    user_id       UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    channel       VARCHAR(20)  NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    sent_at       TIMESTAMP,
    error_message TEXT,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index for finding notifications by hearing
CREATE INDEX idx_notification_logs_hearing ON notification_logs(hearing_id);

-- Index for finding notifications by status (for retry logic)
CREATE INDEX idx_notification_logs_status ON notification_logs(status);