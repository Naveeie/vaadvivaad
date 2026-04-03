-- V6__seed_sample_data.sql
-- Sample court cases and hearings for development and testing
-- These represent realistic Tamil Nadu district court cases

-- Case 1: A civil suit in Chennai
INSERT INTO court_cases (id, cnr_number, case_type, filing_number, filing_date,
    registration_number, registration_date, status, petitioner, respondent,
    court_name, judge_name, created_at, updated_at)
VALUES (
    'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
    'TNCH010012342024',
    'Civil Suit',
    'CS/1234/2024',
    '2024-03-15',
    'OS/567/2024',
    '2024-03-20',
    'PENDING',
    'Rajesh Kumar S/o Ramesh Kumar',
    'Chennai Municipal Corporation',
    'District Court, Chennai',
    'Hon. Justice Priya Sharma',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

-- Case 1 Hearings
INSERT INTO hearings (id, case_id, hearing_date, purpose, detail, created_at)
VALUES
    ('11111111-1111-1111-1111-111111111111',
     'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
     '2024-04-10', 'Filing and Registration',
     'Case filed and registered. Notice issued to respondent.',
     CURRENT_TIMESTAMP),
    ('22222222-2222-2222-2222-222222222222',
     'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
     '2024-06-15', 'Written Statement',
     'Respondent filed written statement. Petitioner to file reply.',
     CURRENT_TIMESTAMP),
    ('33333333-3333-3333-3333-333333333333',
     'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
     '2026-07-20', 'Arguments',
     'Both sides to present arguments.',
     CURRENT_TIMESTAMP);

-- Case 2: A criminal case in Coimbatore
INSERT INTO court_cases (id, cnr_number, case_type, filing_number, filing_date,
    registration_number, registration_date, status, petitioner, respondent,
    court_name, judge_name, created_at, updated_at)
VALUES (
    'b2c3d4e5-f6a7-8901-bcde-f12345678901',
    'TNCB020098762024',
    'Criminal Case',
    'CC/9876/2024',
    '2024-01-10',
    'CC/234/2024',
    '2024-01-15',
    'PENDING',
    'State of Tamil Nadu',
    'Murugan K',
    'Judicial Magistrate Court, Coimbatore',
    'Hon. Justice Venkatesh R',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

-- Case 2 Hearings
INSERT INTO hearings (id, case_id, hearing_date, purpose, detail, created_at)
VALUES
    ('44444444-4444-4444-4444-444444444444',
     'b2c3d4e5-f6a7-8901-bcde-f12345678901',
     '2024-02-20', 'Bail Hearing',
     'Bail application heard. Bail granted with conditions.',
     CURRENT_TIMESTAMP),
    ('55555555-5555-5555-5555-555555555555',
     'b2c3d4e5-f6a7-8901-bcde-f12345678901',
     '2024-05-10', 'Evidence',
     'Prosecution witness examination. 3 witnesses examined.',
     CURRENT_TIMESTAMP),
    ('66666666-6666-6666-6666-666666666666',
     'b2c3d4e5-f6a7-8901-bcde-f12345678901',
     '2026-08-15', 'Cross Examination',
     'Defense to cross-examine prosecution witnesses.',
     CURRENT_TIMESTAMP);

-- Case 3: A disposed case in Madurai
INSERT INTO court_cases (id, cnr_number, case_type, filing_number, filing_date,
    registration_number, registration_date, status, petitioner, respondent,
    court_name, judge_name, created_at, updated_at)
VALUES (
    'c3d4e5f6-a7b8-9012-cdef-123456789012',
    'TNMD030056782023',
    'Motor Accident Claim',
    'MCOP/5678/2023',
    '2023-06-01',
    'MCOP/890/2023',
    '2023-06-10',
    'DISPOSED',
    'Lakshmi D/o Krishnan',
    'National Insurance Company Ltd',
    'Motor Accident Claims Tribunal, Madurai',
    'Hon. Justice Arun Kumar P',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

-- Case 3 Hearings
INSERT INTO hearings (id, case_id, hearing_date, purpose, detail, created_at)
VALUES
    ('77777777-7777-7777-7777-777777777777',
     'c3d4e5f6-a7b8-9012-cdef-123456789012',
     '2023-08-15', 'Claim Petition Filed',
     'Petition filed with medical records and FIR copy.',
     CURRENT_TIMESTAMP),
    ('88888888-8888-8888-8888-888888888888',
     'c3d4e5f6-a7b8-9012-cdef-123456789012',
     '2024-01-20', 'Final Hearing',
     'Compensation of Rs 4,50,000 awarded to petitioner. Case disposed.',
     CURRENT_TIMESTAMP);