ALTER TABLE inference_run
    DROP CONSTRAINT inference_run_stage_check,
    ADD CONSTRAINT inference_run_stage_check CHECK (
        stage IN (
            'NORMALIZE', 'OBSERVE', 'HIERARCHY', 'ELEMENT_BINDING', 'STRUCTURE',
            'DETERMINISTIC_VALIDATE', 'CRITIQUE', 'REPAIR', 'USER_APPROVAL', 'ATOMIC_CREATE'
        )
    );

ALTER TABLE inference_attempt
    DROP CONSTRAINT inference_attempt_attempt_ordinal_check,
    DROP CONSTRAINT inference_attempt_stage_check,
    ADD CONSTRAINT inference_attempt_attempt_ordinal_check
        CHECK (attempt_ordinal BETWEEN 0 AND 4),
    ADD CONSTRAINT inference_attempt_stage_check
        CHECK (stage IN ('OBSERVE', 'HIERARCHY', 'ELEMENT_BINDING', 'STRUCTURE', 'REPAIR'));

ALTER TABLE inference_provider_reservation
    DROP CONSTRAINT inference_provider_reservation_attempt_ordinal_check,
    ADD CONSTRAINT inference_provider_reservation_attempt_ordinal_check
        CHECK (attempt_ordinal BETWEEN 0 AND 4);

