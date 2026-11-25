CREATE TABLE admin_sessions (
    id UUID PRIMARY KEY,
    admin_user_id UUID NOT NULL REFERENCES admin_users(id),
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    expires_at_utc TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at_utc TIMESTAMP WITH TIME ZONE,
    last_used_at_utc TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_admin_sessions_user ON admin_sessions (admin_user_id);
