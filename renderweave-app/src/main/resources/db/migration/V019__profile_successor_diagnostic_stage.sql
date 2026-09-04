ALTER TABLE certification_stage_ledger
    DROP CONSTRAINT certification_stage_ledger_stage_check;

ALTER TABLE certification_stage_ledger
    ALTER COLUMN stage TYPE VARCHAR(40);

ALTER TABLE certification_stage_ledger
    ADD CONSTRAINT certification_stage_ledger_stage_check CHECK (
        stage IN (
            'CANARY_5',
            'DEV_20',
            'FINAL_60',
            'PROFILE_SUCCESSOR_DIAGNOSTIC_1'
        )
    );
