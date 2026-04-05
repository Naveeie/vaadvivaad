-- V8__fix_seed_hearing_next_dates.sql

/*
 * Update seed data hearings to have next_hearing_date values.
 * This gives the NotificationScheduler something to work with in dev.
 *
 * We set next_hearing_date = CURRENT_DATE + 1 for one hearing
 * so that running the scheduler today will find it and fire a reminder.
 * This is dev-only convenience data.
 */
UPDATE hearings
SET next_hearing_date = CURRENT_DATE + INTERVAL '1 day'
WHERE id = (
    SELECT h.id FROM hearings h
    JOIN court_cases c ON h.case_id = c.id
    ORDER BY h.created_at DESC
    LIMIT 1
);