-- Initial normalized relational schema for Workforce Attendance PWA

CREATE TABLE admin_users (
    id UUID PRIMARY KEY,
    username VARCHAR(80) NOT NULL UNIQUE,
    display_name VARCHAR(150) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(40) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE workers (
    id UUID PRIMARY KEY,
    worker_code VARCHAR(40) NOT NULL UNIQUE,
    full_name VARCHAR(160) NOT NULL,
    team_name VARCHAR(100),
    profile_photo_key VARCHAR(255),
    status VARCHAR(30) NOT NULL,
    employment_start_date DATE,
    employment_end_date DATE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE worker_invitations (
    id UUID PRIMARY KEY,
    worker_id UUID NOT NULL REFERENCES workers(id),
    code_hash VARCHAR(128) NOT NULL UNIQUE,
    code_hint VARCHAR(32) NOT NULL,
    expires_at_utc TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at_utc TIMESTAMP WITH TIME ZONE,
    created_by_admin_id UUID REFERENCES admin_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE consent_records (
    id UUID PRIMARY KEY,
    worker_id UUID NOT NULL REFERENCES workers(id),
    consent_version VARCHAR(40) NOT NULL,
    consent_given BOOLEAN NOT NULL,
    notice_summary VARCHAR(500) NOT NULL,
    ip_address_hash VARCHAR(128),
    recorded_at_utc TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE worker_biometrics (
    id UUID PRIMARY KEY,
    worker_id UUID NOT NULL UNIQUE REFERENCES workers(id),
    encrypted_embedding BYTEA NOT NULL,
    encryption_iv BYTEA NOT NULL,
    key_version VARCHAR(32) NOT NULL,
    embedding_model VARCHAR(120) NOT NULL,
    model_version VARCHAR(64) NOT NULL,
    embedding_dimension INTEGER NOT NULL,
    consent_version VARCHAR(40) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE worker_sessions (
    id UUID PRIMARY KEY,
    worker_id UUID NOT NULL REFERENCES workers(id),
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    device_label VARCHAR(160),
    expires_at_utc TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at_utc TIMESTAMP WITH TIME ZONE,
    last_used_at_utc TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE attendance_settings (
    id UUID PRIMARY KEY,
    workplace_name VARCHAR(160) NOT NULL,
    timezone_id VARCHAR(64) NOT NULL,
    default_shift_start_local TIME NOT NULL,
    default_grace_period_minutes INTEGER NOT NULL,
    default_absence_cutoff_local TIME NOT NULL,
    proxy_enabled BOOLEAN NOT NULL,
    max_proxy_per_actor_per_day INTEGER NOT NULL,
    proxy_cap_counts_successful_only BOOLEAN NOT NULL,
    face_match_threshold DOUBLE PRECISION NOT NULL,
    face_model_version VARCHAR(80) NOT NULL,
    effective_from_utc TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by_admin_id UUID REFERENCES admin_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE work_calendar (
    id UUID PRIMARY KEY,
    calendar_date DATE NOT NULL UNIQUE,
    day_type VARCHAR(20) NOT NULL,
    shift_start_local TIME NOT NULL,
    grace_period_minutes INTEGER NOT NULL,
    absence_cutoff_local TIME NOT NULL,
    timezone_id VARCHAR(64) NOT NULL,
    note VARCHAR(255),
    created_by_admin_id UUID REFERENCES admin_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE attendance_records (
    id UUID PRIMARY KEY,
    subject_worker_id UUID NOT NULL REFERENCES workers(id),
    actor_worker_id UUID REFERENCES workers(id),
    attendance_date DATE NOT NULL,
    check_in_at_utc TIMESTAMP WITH TIME ZONE NOT NULL,
    attendance_status VARCHAR(30) NOT NULL,
    attendance_method VARCHAR(30) NOT NULL,
    idempotency_key VARCHAR(128) UNIQUE,
    match_confidence DOUBLE PRECISION,
    device_session_id UUID REFERENCES worker_sessions(id),
    notes VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_attendance_subject_date UNIQUE (subject_worker_id, attendance_date)
);

CREATE TABLE attendance_corrections (
    id UUID PRIMARY KEY,
    attendance_record_id UUID REFERENCES attendance_records(id),
    subject_worker_id UUID NOT NULL REFERENCES workers(id),
    attendance_date DATE NOT NULL,
    previous_status VARCHAR(30),
    new_status VARCHAR(30) NOT NULL,
    previous_check_in_at_utc TIMESTAMP WITH TIME ZONE,
    new_check_in_at_utc TIMESTAMP WITH TIME ZONE,
    reason VARCHAR(500) NOT NULL,
    corrected_by_admin_id UUID REFERENCES admin_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    actor_type VARCHAR(30) NOT NULL,
    actor_id UUID,
    subject_worker_id UUID,
    event_timestamp_utc TIMESTAMP WITH TIME ZONE NOT NULL,
    metadata_json TEXT,
    correlation_id VARCHAR(64)
);

CREATE INDEX idx_workers_status ON workers (status);
CREATE INDEX idx_worker_invitations_worker ON worker_invitations (worker_id);
CREATE INDEX idx_worker_sessions_worker ON worker_sessions (worker_id);
CREATE INDEX idx_work_calendar_date ON work_calendar (calendar_date);
CREATE INDEX idx_attendance_date_subject ON attendance_records (attendance_date, subject_worker_id);
CREATE INDEX idx_attendance_actor_date ON attendance_records (actor_worker_id, attendance_date);
CREATE INDEX idx_audit_events_ts ON audit_events (event_timestamp_utc DESC);
CREATE INDEX idx_audit_events_subject ON audit_events (subject_worker_id);
CREATE INDEX idx_audit_events_type ON audit_events (event_type);
