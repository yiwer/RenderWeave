ALTER TABLE inference_attempt
    DROP CONSTRAINT inference_attempt_attempt_ordinal_check,
    ADD CONSTRAINT inference_attempt_attempt_ordinal_check
        CHECK (attempt_ordinal BETWEEN 0 AND 6);

ALTER TABLE inference_provider_reservation
    DROP CONSTRAINT inference_provider_reservation_attempt_ordinal_check,
    ADD CONSTRAINT inference_provider_reservation_attempt_ordinal_check
        CHECK (attempt_ordinal BETWEEN 0 AND 6);
