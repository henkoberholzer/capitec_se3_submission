CREATE TABLE document (
    id                  UUID PRIMARY KEY,
    created_at          TIMESTAMPTZ  NOT NULL,
    created_by          VARCHAR(255) NOT NULL,
    revoked_at          TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ  NOT NULL,
    revoked_by          VARCHAR(255),
    token_hash          VARCHAR(64)  NOT NULL UNIQUE,
    storage_key         TEXT         NOT NULL,
    file_size_bytes     BIGINT       NOT NULL,
    sha256_hash         VARCHAR(64)  NOT NULL,
    max_downloads       INTEGER      NOT NULL,
    download_count      INTEGER      NOT NULL,
    status              VARCHAR(20)  NOT NULL
);

CREATE INDEX idx_document_token_hash    ON document (token_hash);
CREATE INDEX idx_document_status_expiry ON document (status, expires_at);

CREATE TABLE audit_events (
    id              BIGSERIAL    PRIMARY KEY,
    document_id     UUID         NOT NULL REFERENCES document(id),
    event_type      VARCHAR(30)  NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    result          VARCHAR(10)  NOT NULL,
    reason_code     VARCHAR(50),
    caller_id       VARCHAR(255),
    metadata        JSONB
);

CREATE INDEX idx_audit_document_id  ON audit_events (document_id);
CREATE INDEX idx_audit_occurred_at  ON audit_events (occurred_at);

CREATE TABLE outbox_events (
    id              BIGSERIAL    PRIMARY KEY,
    created_at      TIMESTAMPTZ  NOT NULL,
    process_after   TIMESTAMPTZ  NOT NULL,
    locked_until    TIMESTAMPTZ,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(30)  NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    attempt_count   INTEGER      NOT NULL,
    max_attempts    INTEGER      NOT NULL
);

CREATE INDEX idx_outbox_claimable
    ON outbox_events (process_after)
    WHERE status = 'PENDING';
