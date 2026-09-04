CREATE FUNCTION renderweave_valid_inference_rejection_envelope(envelope JSONB)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
PARALLEL SAFE
AS $$
DECLARE
    details JSONB;
    canonical JSONB;
    declared_count INTEGER;
    primary_code TEXT;
BEGIN
    IF envelope IS NULL THEN
        RETURN TRUE;
    END IF;
    IF jsonb_typeof(envelope) <> 'object' THEN
        RETURN FALSE;
    END IF;
    IF (SELECT count(*) FROM jsonb_object_keys(envelope)) <> 4
            OR NOT envelope ?& ARRAY[
                'primaryCode', 'earliestStage', 'detailCodes', 'detailCodeCount'
            ] THEN
        RETURN FALSE;
    END IF;
    IF jsonb_typeof(envelope -> 'primaryCode') <> 'string'
            OR jsonb_typeof(envelope -> 'earliestStage') <> 'string'
            OR jsonb_typeof(envelope -> 'detailCodes') <> 'array'
            OR jsonb_typeof(envelope -> 'detailCodeCount') <> 'number'
            OR envelope ->> 'earliestStage' <> 'OBSERVE' THEN
        RETURN FALSE;
    END IF;

    details := envelope -> 'detailCodes';
    declared_count := (envelope ->> 'detailCodeCount')::INTEGER;
    primary_code := envelope ->> 'primaryCode';

    SELECT COALESCE(jsonb_agg(item.code ORDER BY item.ordinal), '[]'::jsonb)
    INTO canonical
    FROM (VALUES
        (1, 'VISUAL_GROUNDING_REGION_ENTRY_INVALID'),
        (2, 'VISUAL_GROUNDING_REGION_ID_INVALID'),
        (3, 'VISUAL_GROUNDING_REGION_PARENT_ID_INVALID'),
        (4, 'VISUAL_GROUNDING_REGION_MULTIPLICITY_INVALID'),
        (5, 'VISUAL_GROUNDING_REGION_READING_ORDER_INVALID'),
        (6, 'VISUAL_GROUNDING_REGION_REPEAT_GROUP_ID_INVALID'),
        (7, 'VISUAL_GROUNDING_REGION_EVIDENCE_INVALID')
    ) AS item(ordinal, code)
    WHERE details ? item.code;

    IF details <> canonical
            OR declared_count <> jsonb_array_length(details) THEN
        RETURN FALSE;
    END IF;
    IF primary_code = 'VISUAL_GROUNDING_REGION_FIELDS_INVALID' THEN
        RETURN declared_count BETWEEN 2 AND 7;
    END IF;
    IF primary_code = 'VISUAL_GROUNDING_REGION_UNCLASSIFIED' THEN
        RETURN declared_count = 0;
    END IF;
    RETURN FALSE;
EXCEPTION
    WHEN invalid_text_representation OR numeric_value_out_of_range THEN
        RETURN FALSE;
END;
$$;

ALTER TABLE inference_attempt
    ADD COLUMN rejection_envelope JSONB,
    ADD CONSTRAINT inference_attempt_rejection_envelope_check
        CHECK (renderweave_valid_inference_rejection_envelope(rejection_envelope)),
    ADD CONSTRAINT inference_attempt_rejection_envelope_status_check
        CHECK (rejection_envelope IS NULL OR (
            status = 'REJECTED'
            AND stage = 'OBSERVE'
            AND outcome_code = 'LIVE_VISUAL_ANALYSIS_REJECTED'
        ));

COMMENT ON COLUMN inference_attempt.rejection_envelope IS
    'Optional closed payload-free primary/detail rejection provenance; raw model values are forbidden.';
