## Part 1: Database Schema Design

Before writing any entity, we design the schema on paper. **Schema-first thinking** is what separates seniors from juniors.

### The Domain Model — What Data Does VaadVivaad Track?

Think about the real world:

```
A USER registers on VaadVivaad
    → They SUBSCRIBE to one or more COURT CASES
        → Each COURT CASE has a CNR number and case details
            → Each COURT CASE has multiple HEARINGS
                → For each upcoming HEARING, we send NOTIFICATIONS
```

### Entity Relationship Diagram

```
┌──────────────┐       ┌──────────────────┐       ┌──────────────────┐
│    users     │       │  subscriptions   │       │   court_cases    │
├──────────────┤       ├──────────────────┤       ├──────────────────┤
│ id (PK, UUID)│──┐    │ id (PK, UUID)    │    ┌──│ id (PK, UUID)    │
│ email        │  └───>│ user_id (FK)     │    │  │ cnr_number (UQ)  │
│ password_hash│       │ case_id (FK)     │<───┘  │ case_type        │
│ full_name    │       │ notify_whatsapp  │       │ filing_number    │
│ phone_number │       │ notify_sms       │       │ filing_date      │
│ role         │       │ created_at       │       │ registration_no  │
│ created_at   │       └──────────────────┘       │ registration_date│
│ updated_at   │                                  │ status           │
└──────────────┘                                  │ petitioner       │
                                                  │ respondent       │
                                                  │ court_name       │
                                                  │ judge_name       │
                                                  │ last_scraped_at  │
                                                  │ created_at       │
                                                  │ updated_at       │
                                                  └──────┬───────────┘
                                                         │
                                                         │ 1:N
                                                         ▼
                                                  ┌──────────────────┐
                                                  │    hearings      │
                                                  ├──────────────────┤
                                                  │ id (PK, UUID)    │
                                                  │ case_id (FK)     │
                                                  │ hearing_date     │
                                                  │ purpose          │
                                                  │ detail           │
                                                  │ ai_summary_hindi │
                                                  │ created_at       │
                                                  └──────┬───────────┘
                                                         │
                                                         │ 1:N
                                                         ▼
                                                  ┌──────────────────┐
                                                  │notification_logs │
                                                  ├──────────────────┤
                                                  │ id (PK, UUID)    │
                                                  │ hearing_id (FK)  │
                                                  │ user_id (FK)     │
                                                  │ channel          │
                                                  │ status           │
                                                  │ sent_at          │
                                                  │ error_message    │
                                                  │ created_at       │
                                                  └──────────────────┘
```

### The Relationships Explained

```
users ←──── 1:N ────→ subscriptions ←──── N:1 ────→ court_cases
   "A user subscribes to many cases. A case has many subscribers."
   This is a MANY-TO-MANY relationship, modeled with a JOIN TABLE (subscriptions).
   We use an explicit join entity (not @ManyToMany) because subscriptions
   carry extra data: notify_whatsapp, notify_sms.

court_cases ←── 1:N ──→ hearings
   "One case has many hearings."

hearings ←── 1:N ──→ notification_logs
   "One hearing triggers many notifications (one per subscriber)."
```


idx_users_email              → Every login does WHERE email = ?
idx_court_cases_cnr          → Every CNR lookup does WHERE cnr_number = ?
idx_court_cases_status       → Scheduler queries WHERE status = 'PENDING'
idx_hearings_case_id         → Fetching hearings for a case (FK lookup)
idx_hearings_date            → Scheduler finds WHERE hearing_date = tomorrow
idx_subscriptions_user_id    → Dashboard: show user's subscriptions
idx_subscriptions_case_id    → Notification: find all subscribers of a case
idx_notification_logs_status → Retry job: find WHERE status = 'FAILED'