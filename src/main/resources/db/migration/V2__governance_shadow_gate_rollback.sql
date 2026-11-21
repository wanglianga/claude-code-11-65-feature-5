-- Governance layer: shadow scoring, temporal ordering rules, release gates,
-- targeted rollbacks and sharded batch backfills.
--
-- The release pipeline enforced by these tables is deliberately "shadow first":
-- a candidate definition version is scored against frozen case snapshots before
-- it can be approved, and an approval whose catalog fingerprint drifted while
-- the review was open is invalidated and can never reach production.

-- ---------------------------------------------------------------------------
-- Definition effective time: the third clock of the temporal rule set.
-- observed_at <= ingested_at <= scoring_cutoff, and the definition must already
-- be in force (effective_at <= scoring_cutoff) when it scores a production case.
-- ---------------------------------------------------------------------------
alter table feature_definition_version add column effective_at timestamptz;

update feature_definition_version
   set effective_at = coalesce(approved_at, created_at)
 where effective_at is null;

alter table feature_definition_version alter column effective_at set not null;

-- Shadow runs and batch backfills reuse the feature_run ledger; rollback coordination
-- needs an explicit terminal state for shards stopped mid-flight.
alter table feature_run drop constraint feature_run_mode_check;
alter table feature_run
    add constraint feature_run_mode_check
    check (mode in ('ORIGINAL','REPLAY','CONTROLLED_BACKFILL','SHADOW'));

alter table feature_run drop constraint feature_run_trigger_type_check;
alter table feature_run
    add constraint feature_run_trigger_type_check
    check (trigger_type in (
        'CASE_SCORING','BACKFILL_REQUEST','IMPACT_ANALYSIS','REPRODUCTION',
        'SHADOW_SCORING','BATCH_BACKFILL'));

alter table feature_run drop constraint feature_run_status_check;
alter table feature_run
    add constraint feature_run_status_check
    check (status in (
        'QUEUED','RUNNING','SUCCEEDED','LEAKAGE_BLOCKED','INSUFFICIENT_DATA','FAILED','TERMINATED'));

-- ---------------------------------------------------------------------------
-- Shadow scoring: one row per frozen case re-scored with candidate definitions.
-- Shadow runs never write a scoring_snapshot and never change claim_case status.
-- ---------------------------------------------------------------------------
create table shadow_run (
    id                      bigserial primary key,
    campaign_code           varchar(80) not null,
    case_id                 bigint not null references claim_case(id),
    snapshot_id             bigint not null references scoring_snapshot(id),
    baseline_run_id         bigint references feature_run(id),
    release_label           varchar(120),
    status                  varchar(24) not null
                                check (status in ('QUEUED','RUNNING','COMPLETED','FAILED')),
    score_diff_threshold    numeric(18,6) not null,
    reversal_rate_limit     numeric(8,6) not null default 0,
    cutoff_at               timestamptz not null,
    temporal_verdict        varchar(24) not null default 'NOT_EVALUATED'
                                check (temporal_verdict in
                                       ('NOT_EVALUATED','PASS','PASS_WITH_WARNINGS','BLOCKED')),
    chain_complete          boolean not null default true,
    blocking_finding_count  int not null default 0,
    warning_finding_count   int not null default 0,
    summary                 jsonb,
    requested_by            varchar(120),
    reason                  text,
    created_at              timestamptz not null default now(),
    completed_at            timestamptz
);

create index idx_shadow_run_case on shadow_run(case_id, created_at desc);

create table shadow_run_feature (
    id                      bigserial primary key,
    shadow_run_id           bigint not null references shadow_run(id) on delete cascade,
    feature_code            varchar(64) not null,
    baseline_version_id     bigint references feature_definition_version(id),
    baseline_version_no     int,
    baseline_value          numeric(18,6),
    baseline_status         varchar(24),
    candidate_version_id    bigint not null references feature_definition_version(id),
    candidate_version_no    int not null,
    candidate_value         numeric(18,6),
    candidate_status        varchar(24),
    abs_delta               numeric(18,6),
    relative_delta          numeric(18,6),
    reversed                boolean not null default false,
    over_threshold          boolean not null default false,
    input_chain_complete    boolean not null default true,
    classification          varchar(32) not null
                                check (classification in (
                                    'WITHIN_THRESHOLD',
                                    'BUSINESS_DIFFERENCE',
                                    'TIME_TRAVEL_FAILURE',
                                    'MISSING_DATA_DIFFERENCE',
                                    'NOT_COMPUTABLE')),
    attribution             jsonb,
    unique (shadow_run_id, feature_code)
);

create index idx_shadow_feature_run on shadow_run_feature(shadow_run_id);

alter table feature_run add column shadow_run_id bigint references shadow_run(id);

-- Explicit per-feature definition versions a shadow run evaluates. The target feature points
-- at its candidate version; every other feature keeps the version the case locked at intake.
create table shadow_run_definition (
    id                      bigserial primary key,
    shadow_run_id           bigint not null references shadow_run(id) on delete cascade,
    feature_code            varchar(64) not null,
    definition_version_id   bigint not null references feature_definition_version(id),
    unique (shadow_run_id, feature_code)
);

create index idx_shadow_definition_run on shadow_run_definition(shadow_run_id);

-- ---------------------------------------------------------------------------
-- Release gates: a gate binds a candidate version to the exact catalog
-- fingerprint captured at submission. Any catalog mutation while the gate is
-- open invalidates it, so the approved artefact can never silently change.
-- ---------------------------------------------------------------------------
create table release_gate (
    id                          bigserial primary key,
    feature_definition_id       bigint not null references feature_definition(id),
    candidate_version_id        bigint not null references feature_definition_version(id),
    candidate_checksum          varchar(64) not null,
    release_label               varchar(120),
    status                      varchar(24) not null
                                    check (status in (
                                        'PENDING_APPROVAL','APPROVED','REJECTED','INVALIDATED')),
    catalog_fingerprint         varchar(64) not null,
    threshold_config            jsonb not null,
    shadow_run_ids              jsonb not null,
    temporal_passed             boolean not null default false,
    threshold_passed            boolean not null default false,
    blocking_finding_count      int not null default 0,
    reversal_count              int not null default 0,
    business_difference_count   int not null default 0,
    max_abs_delta               numeric(18,6),
    checks                      jsonb,
    decision_by                 varchar(120),
    decided_at                  timestamptz,
    decision_note               text,
    invalidate_reason           text,
    created_at                  timestamptz not null default now(),
    invalidated_at              timestamptz
);

create index idx_gate_version on release_gate(candidate_version_id);
create index idx_gate_status on release_gate(status, created_at desc);

-- ---------------------------------------------------------------------------
-- Findings of the temporal ordering rule set (shared by shadow runs and gates).
-- ---------------------------------------------------------------------------
create table temporal_order_finding (
    id              bigserial primary key,
    shadow_run_id   bigint references shadow_run(id) on delete cascade,
    gate_id         bigint references release_gate(id) on delete cascade,
    rule_code       varchar(64) not null,
    severity        varchar(16) not null check (severity in ('INFO','WARNING','BLOCKING')),
    subject_type    varchar(24) not null,
    subject_ref     varchar(200),
    expected_order  text,
    actual_order    text,
    message         text not null,
    passed          boolean not null,
    detail          jsonb,
    created_at      timestamptz not null default now()
);

create index idx_temporal_shadow on temporal_order_finding(shadow_run_id);
create index idx_temporal_gate on temporal_order_finding(gate_id);

-- ---------------------------------------------------------------------------
-- Targeted rollbacks: disable reads of one definition version, optionally
-- restricted to an explicit case list. Frozen snapshots are never touched.
-- ---------------------------------------------------------------------------
create table feature_rollback (
    id                          bigserial primary key,
    feature_definition_id       bigint not null references feature_definition(id),
    rolled_back_version_id      bigint not null references feature_definition_version(id),
    scope_type                  varchar(16) not null
                                    check (scope_type in ('ALL_CASES','CASE_LIST')),
    case_scope                  jsonb,
    status                      varchar(16) not null check (status in ('ACTIVE','REVOKED')),
    reason                      text not null,
    requested_by                varchar(120),
    terminated_shard_count      int not null default 0,
    quarantined_shard_count     int not null default 0,
    created_at                  timestamptz not null default now(),
    revoked_at                  timestamptz,
    revoke_note                 text
);

create index idx_rollback_version on feature_rollback(rolled_back_version_id, status);
create index idx_rollback_status on feature_rollback(status);

-- ---------------------------------------------------------------------------
-- Sharded batch backfills. Each shard depends on an explicit set of definition
-- versions, so a targeted rollback can terminate only the shards that read the
-- rolled-back version and preserve every other shard's result.
-- ---------------------------------------------------------------------------
create table backfill_batch (
    id              bigserial primary key,
    batch_code      varchar(80) not null unique,
    status          varchar(24) not null
                        check (status in (
                            'PLANNED','RUNNING','COMPLETED','PARTIALLY_TERMINATED','TERMINATED')),
    requested_by    varchar(120),
    note            text,
    created_at      timestamptz not null default now(),
    started_at      timestamptz,
    finished_at     timestamptz
);

create table backfill_shard (
    id                      bigserial primary key,
    batch_id                bigint not null references backfill_batch(id) on delete cascade,
    shard_no                int not null,
    case_id                 bigint not null references claim_case(id),
    feature_code            varchar(64) not null,
    depends_on_version_ids  jsonb not null,
    status                  varchar(24) not null
                                check (status in (
                                    'QUEUED','RUNNING','SUCCEEDED','FAILED',
                                    'TERMINATED_BY_ROLLBACK','QUARANTINED')),
    feature_run_id          bigint references feature_run(id),
    termination_reason      text,
    result                  jsonb,
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now(),
    unique (batch_id, shard_no)
);

create index idx_shard_batch on backfill_shard(batch_id, status);
create index idx_shard_case on backfill_shard(case_id);
