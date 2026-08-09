-- The product entry page resumes durable inference runs newest first. Keep this summary query
-- independent from the worker claim indexes and avoid sorting the full run history.
CREATE INDEX inference_run_recent_idx
    ON inference_run (created_at DESC, run_id DESC);
