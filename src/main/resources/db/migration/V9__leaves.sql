-- Add leave quotas, auto-leave-after-cutoff toggle, and leave_records table
ALTER TABLE attendance_settings ADD COLUMN default_paid_leave_quota INTEGER NOT NULL DEFAULT 12;
ALTER TABLE attendance_settings ADD COLUMN default_annual_leave_quota INTEGER NOT NULL DEFAULT 12;
ALTER TABLE attendance_settings ADD COLUMN auto_mark_leave_after_cutoff BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE attendance_settings ADD COLUMN auto_leave_effective_from_date DATE NOT NULL DEFAULT CURRENT_DATE;

CREATE TABLE leave_records (
    id UUID PRIMARY KEY,
    worker_id UUID NOT NULL REFERENCES workers(id),
    leave_date DATE NOT NULL,
    leave_type VARCHAR(30) NOT NULL,
    source VARCHAR(30) NOT NULL,
    notes VARCHAR(500),
    idempotency_key VARCHAR(128) UNIQUE,
    match_confidence DOUBLE PRECISION,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_leave_worker_date UNIQUE (worker_id, leave_date)
);

CREATE INDEX idx_leave_records_date ON leave_records (leave_date);
CREATE INDEX idx_leave_records_worker ON leave_records (worker_id);
