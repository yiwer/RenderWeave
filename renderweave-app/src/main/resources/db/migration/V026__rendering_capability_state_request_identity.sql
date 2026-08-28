ALTER TABLE rendering_capability_state
    ADD CONSTRAINT rendering_capability_state_request_unique UNIQUE (render_request_id);
