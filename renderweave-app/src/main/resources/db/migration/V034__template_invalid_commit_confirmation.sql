create table template_invalid_commit_confirmation (
    confirmation_token char(64) primary key,
    operation varchar(16) not null,
    actor_id varchar(256) not null,
    owner_scope varchar(256) not null,
    template_id varchar(128) not null,
    expected_revision bigint not null,
    schema_key varchar(128) not null,
    schema_version_tag varchar(64) not null,
    content_hash varchar(71) not null,
    problem_fingerprint char(64) not null,
    dependency_snapshot_fingerprint char(64) not null,
    expires_at timestamptz not null,
    issued_at timestamptz not null default clock_timestamp(),
    constraint fk_template_invalid_commit_confirmation_template
        foreign key (template_id)
        references template_aggregate (template_id)
        on delete cascade,
    constraint ck_template_invalid_commit_confirmation_token
        check (confirmation_token ~ '^[0-9a-f]{64}$'),
    constraint ck_template_invalid_commit_confirmation_operation
        check (operation = 'SAVE'),
    constraint ck_template_invalid_commit_confirmation_expected_revision
        check (expected_revision >= 0),
    constraint ck_template_invalid_commit_confirmation_content_hash
        check (content_hash ~ '^sha256:[0-9a-f]{64}$'),
    constraint ck_template_invalid_commit_confirmation_problem_fingerprint
        check (problem_fingerprint ~ '^[0-9a-f]{64}$'),
    constraint ck_template_invalid_commit_confirmation_dependency_fingerprint
        check (dependency_snapshot_fingerprint ~ '^[0-9a-f]{64}$'),
    constraint ck_template_invalid_commit_confirmation_expiry
        check (expires_at > issued_at)
);

create index ix_template_invalid_commit_confirmation_expiry
    on template_invalid_commit_confirmation (expires_at);
