CREATE TABLE gateway_assertion_replay (
    jti VARCHAR(128) PRIMARY KEY CHECK (jti ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    actor_id VARCHAR(128) NOT NULL
        CHECK (actor_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    request_id VARCHAR(128) NOT NULL
        CHECK (request_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    method VARCHAR(8) NOT NULL CHECK (method ~ '^[A-Z]{3,8}$'),
    request_path VARCHAR(1024) NOT NULL CHECK (request_path LIKE '/%'),
    idempotency_key_digest CHAR(64) NOT NULL
        CHECK (idempotency_key_digest ~ '^[0-9a-f]{64}$'),
    key_id VARCHAR(128) NOT NULL
        CHECK (key_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at > issued_at),
    CHECK (expires_at <= issued_at + INTERVAL '60 seconds'),
    CHECK (accepted_at >= issued_at - INTERVAL '30 seconds'),
    CHECK (accepted_at <= expires_at + INTERVAL '30 seconds')
);

CREATE INDEX gateway_assertion_replay_expiry
    ON gateway_assertion_replay (expires_at);
