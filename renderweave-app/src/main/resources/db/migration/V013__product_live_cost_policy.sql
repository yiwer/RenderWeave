ALTER TABLE inference_run
    ADD COLUMN cost_limit_micros_cny BIGINT NULL
        CHECK (cost_limit_micros_cny IS NULL OR cost_limit_micros_cny > 0);

-- Product runs keep append-only reservation telemetry without inheriting the finite P5 canary ledger.
-- Per-run optional limits and the Profile's three-call ceiling remain the operative product bounds.
INSERT INTO inference_provider_budget (
    budget_key, maximum_attempts, maximum_cost_micros_cny, created_at
) VALUES (
    'product-live', 2000000000, 9000000000000000000, TIMESTAMPTZ '2026-08-10 00:00:00+00'
);
