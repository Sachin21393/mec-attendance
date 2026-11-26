-- Revoke all legacy device-bound worker sessions on deploy so already-installed PWAs drop session lock automatically
UPDATE worker_sessions
SET revoked_at_utc = CURRENT_TIMESTAMP
WHERE revoked_at_utc IS NULL;
