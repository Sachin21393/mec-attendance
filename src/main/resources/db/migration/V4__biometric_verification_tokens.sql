CREATE TABLE biometric_verification_tokens (
    id UUID PRIMARY KEY,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    actor_worker_id UUID NOT NULL REFERENCES workers(id),
    subject_worker_id UUID NOT NULL REFERENCES workers(id),
    verification_mode VARCHAR(20) NOT NULL,
    match_confidence DOUBLE PRECISION NOT NULL,
    mock_mode BOOLEAN NOT NULL,
    expires_at_utc TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at_utc TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_bvt_actor_expires ON biometric_verification_tokens (actor_worker_id, expires_at_utc);
