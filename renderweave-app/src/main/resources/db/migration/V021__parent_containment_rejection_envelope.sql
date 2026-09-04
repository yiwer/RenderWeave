CREATE OR REPLACE FUNCTION renderweave_valid_inference_rejection_envelope(envelope JSONB)
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

    IF primary_code IN (
        'VISUAL_GROUNDING_REGION_FIELDS_INVALID',
        'VISUAL_GROUNDING_REGION_UNCLASSIFIED'
    ) THEN
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
        RETURN declared_count = 0;
    END IF;

    IF primary_code = 'VISUAL_GROUNDING_PARENT_CONTAINMENT_CLASSIFIED' THEN
        SELECT COALESCE(jsonb_agg(item.code ORDER BY item.ordinal), '[]'::jsonb)
        INTO canonical
        FROM (VALUES
            (1, 'VISUAL_GROUNDING_PARENT_CONTAINMENT_ITEM_ZERO_COMPATIBLE'),
            (2, 'VISUAL_GROUNDING_PARENT_CONTAINMENT_ITEM_AMBIGUOUS_COMPATIBLE'),
            (3, 'VISUAL_GROUNDING_PARENT_CONTAINMENT_NON_ITEM_ZERO_COMPATIBLE'),
            (4, 'VISUAL_GROUNDING_PARENT_CONTAINMENT_NON_ITEM_AMBIGUOUS_COMPATIBLE'),
            (5, 'VISUAL_GROUNDING_PARENT_CONTAINMENT_ATOMIC_ROLLBACK'),
            (6, 'VISUAL_GROUNDING_PARENT_CONTAINMENT_UNCLASSIFIED')
        ) AS item(ordinal, code)
        WHERE details ? item.code;

        IF details <> canonical
                OR declared_count <> jsonb_array_length(details)
                OR declared_count < 1 THEN
            RETURN FALSE;
        END IF;
        IF details ? 'VISUAL_GROUNDING_PARENT_CONTAINMENT_ATOMIC_ROLLBACK'
                OR details ? 'VISUAL_GROUNDING_PARENT_CONTAINMENT_UNCLASSIFIED' THEN
            RETURN declared_count = 1;
        END IF;
        RETURN declared_count <= 4;
    END IF;
    RETURN FALSE;
EXCEPTION
    WHEN invalid_text_representation OR numeric_value_out_of_range THEN
        RETURN FALSE;
END;
$$;
