ALTER TABLE inference_candidate
    ADD COLUMN final_json JSONB NULL,
    ADD COLUMN applied_at TIMESTAMPTZ NULL,
    ADD CONSTRAINT inference_candidate_final_snapshot_check CHECK (
        (final_json IS NULL AND applied_at IS NULL)
        OR (final_json IS NOT NULL AND applied_at IS NOT NULL)
    ),
    ADD CONSTRAINT inference_candidate_final_size_check CHECK (
        final_json IS NULL OR octet_length(final_json::text) <= 2097152
    );
