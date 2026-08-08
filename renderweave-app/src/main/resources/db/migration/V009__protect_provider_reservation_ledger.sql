ALTER TABLE inference_provider_reservation
    DROP CONSTRAINT inference_provider_reservation_run_id_fkey;

COMMENT ON COLUMN inference_provider_reservation.run_id IS
    'Immutable audit reference; intentionally retained after inference_run deletion.';
