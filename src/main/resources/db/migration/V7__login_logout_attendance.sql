-- Add configurable shift end / logout time and per-day check-out (logout) tracking
ALTER TABLE attendance_settings ADD COLUMN default_shift_end_local TIME NOT NULL DEFAULT '18:00:00';

ALTER TABLE work_calendar ADD COLUMN shift_end_local TIME NOT NULL DEFAULT '18:00:00';

ALTER TABLE attendance_records ADD COLUMN check_out_at_utc TIMESTAMP WITH TIME ZONE;
ALTER TABLE attendance_records ADD COLUMN logout_status VARCHAR(30);
ALTER TABLE attendance_records ADD COLUMN logout_idempotency_key VARCHAR(128);
ALTER TABLE attendance_records ADD COLUMN logout_match_confidence DOUBLE PRECISION;

ALTER TABLE attendance_records ADD CONSTRAINT uq_attendance_logout_idem UNIQUE (logout_idempotency_key);
