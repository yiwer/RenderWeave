-- P6 capacity baseline: keep the default card-list sorts bounded before joining/loading JSON.
-- Separate directions preserve the stable ascending SchemaKey tie-breaker in both API sorts.
CREATE INDEX schema_draft_active_updated_desc_idx
    ON schema_draft (updated_at DESC, schema_key ASC)
    WHERE deleted_at IS NULL;

CREATE INDEX schema_draft_active_updated_asc_idx
    ON schema_draft (updated_at ASC, schema_key ASC)
    WHERE deleted_at IS NULL;

CREATE INDEX static_schema_origin_published_desc_idx
    ON static_schema (origin, published_at DESC, schema_key ASC, version_tag ASC);

CREATE INDEX static_schema_origin_published_asc_idx
    ON static_schema (origin, published_at ASC, schema_key ASC, version_tag ASC);

-- The worker predicate starts with network capability and then needs queue age ordering. Keep
-- state/lease in the index for eligibility filtering while preserving created_at/run_id order.
CREATE INDEX inference_run_network_claim_idx
    ON inference_run (
        ((profile_snapshot ->> 'networkAllowed')::boolean),
        created_at ASC,
        run_id ASC
    )
    INCLUDE (state, lease_expires_at)
    WHERE state IN ('QUEUED', 'RUNNING');
