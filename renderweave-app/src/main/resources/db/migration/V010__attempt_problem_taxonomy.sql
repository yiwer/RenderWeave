ALTER TABLE inference_attempt
    ADD COLUMN problem_code_counts JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD CONSTRAINT inference_attempt_problem_code_counts_object_check
        CHECK (jsonb_typeof(problem_code_counts) = 'object'),
    ADD CONSTRAINT inference_attempt_problem_code_counts_size_check
        CHECK (octet_length(problem_code_counts::text) <= 16384);
