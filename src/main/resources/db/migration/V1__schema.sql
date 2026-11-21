-- Point-in-time feature service schema.
-- Two clocks are stored for every warehouse record:
--   observed_at  : when the phenomenon happened (acquisition / period end / survey time)
--   ingested_at  : when the record became available to the platform (time-travel cutoff)
-- Revisions never overwrite their predecessor; they form an explicit chain with their own ingest time.

create table feature_definition (
    id                  bigserial primary key,
    code                varchar(64) not null unique,
    name                varchar(200) not null,
    description         text,
    unit                varchar(32),
    source_types        jsonb not null,
    status              varchar(16) not null check (status in ('DRAFT','APPROVED','RETIRED')),
    current_version_id  bigint,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);

create table feature_definition_version (
    id                      bigserial primary key,
    feature_definition_id   bigint not null references feature_definition(id),
    version_no              int not null,
    formula                 text not null,
    required                boolean not null default true,
    availability_lag_seconds bigint not null default 0,
    as_of_policy            varchar(24) not null default 'AS_OF_CUTOFF'
                                check (as_of_policy in ('AS_OF_CUTOFF','LATEST_AVAILABLE')),
    lookback_days           int not null default 0,
    status                  varchar(16) not null check (status in ('DRAFT','APPROVED','SUPERSEDED','RETIRED')),
    approved_by             varchar(120),
    approved_at             timestamptz,
    created_at              timestamptz not null default now(),
    checksum                varchar(64),
    notes                   text,
    supersedes_version_id   bigint references feature_definition_version(id),
    unique (feature_definition_id, version_no)
);

alter table feature_definition
    add constraint fk_feature_current_version
    foreign key (current_version_id) references feature_definition_version(id);

create index idx_fdv_definition on feature_definition_version(feature_definition_id, version_no desc);

-- ---------------------------------------------------------------------------
-- Reference geography
-- ---------------------------------------------------------------------------

create table parcel (
    id            bigserial primary key,
    parcel_code   varchar(64) not null unique,
    name          varchar(200) not null,
    region        varchar(120),
    crop_code     varchar(32),
    created_at    timestamptz not null default now()
);

-- Boundary versions are alternatives for the same parcel. A new survey never
-- rewrites the old geometry; it is appended with its own observation/ingest clocks.
create table parcel_boundary_version (
    id                  bigserial primary key,
    parcel_id           bigint not null references parcel(id),
    version_no          int not null,
    geometry_geojson    jsonb not null,
    area_ha             numeric(14,4) not null,
    valid_from          date,
    valid_to            date,
    observed_at         timestamptz not null,
    ingested_at         timestamptz not null,
    revision_of_id      bigint references parcel_boundary_version(id),
    survey_note         text,
    created_at          timestamptz not null default now(),
    unique (parcel_id, version_no)
);

create index idx_pbv_parcel on parcel_boundary_version(parcel_id, version_no desc);
create index idx_pbv_ingest on parcel_boundary_version(ingested_at);

create table weather_station (
    id            bigserial primary key,
    station_code  varchar(64) not null unique,
    name          varchar(200) not null,
    lon           double precision not null,
    lat           double precision not null,
    created_at    timestamptz not null default now()
);

-- Which station(s) represent a parcel. Weights allow simple area weighting.
create table parcel_weather_link (
    parcel_id   bigint not null references parcel(id),
    station_id  bigint not null references weather_station(id),
    weight      numeric(8,4) not null default 1.0 check (weight > 0),
    primary key (parcel_id, station_id)
);

-- ---------------------------------------------------------------------------
-- Point-in-time warehouse
-- ---------------------------------------------------------------------------

create table satellite_scene (
    id              bigserial primary key,
    scene_key       varchar(80) not null,
    revision_no     int not null default 1,
    acquisition_at  timestamptz not null,
    ingested_at     timestamptz not null,
    cloud_cover_pct numeric(6,2),
    footprint_geojson jsonb not null,
    source          varchar(120),
    revision_of_id  bigint references satellite_scene(id),
    superseded_by_id bigint references satellite_scene(id),
    status          varchar(16) not null default 'ACTIVE' check (status in ('ACTIVE','SUPERSEDED','REJECTED')),
    created_at      timestamptz not null default now(),
    unique (scene_key, revision_no)
);

create index idx_scene_acq on satellite_scene(acquisition_at);
create index idx_scene_ingest on satellite_scene(ingested_at);

create table satellite_scene_cell (
    id          bigserial primary key,
    scene_id    bigint not null references satellite_scene(id) on delete cascade,
    cell_key    varchar(80) not null,
    lon         double precision not null,
    lat         double precision not null,
    ndvi        numeric(8,5) not null check (ndvi >= -1 and ndvi <= 1),
    area_ha     numeric(12,4) not null default 0.09,
    unique (scene_id, cell_key)
);

create index idx_scene_cell_scene on satellite_scene_cell(scene_id);

create table weather_observation (
    id              bigserial primary key,
    station_id      bigint not null references weather_station(id),
    external_key    varchar(100) not null,
    period_start    timestamptz not null,
    period_end      timestamptz not null,
    rainfall_mm     numeric(10,2) not null,
    temp_mean_c     numeric(6,2),
    observed_at     timestamptz not null,
    ingested_at     timestamptz not null,
    revision_no     int not null default 1,
    revision_of_id  bigint references weather_observation(id),
    superseded_by_id bigint references weather_observation(id),
    status          varchar(16) not null default 'ACTIVE' check (status in ('ACTIVE','SUPERSEDED','REJECTED')),
    source          varchar(120),
    created_at      timestamptz not null default now(),
    unique (station_id, external_key, revision_no)
);

create index idx_weather_period on weather_observation(station_id, period_start, period_end);
create index idx_weather_ingest on weather_observation(ingested_at);

create table planting_declaration (
    id              bigserial primary key,
    declaration_no  varchar(80) not null,
    parcel_id       bigint not null references parcel(id),
    crop_code       varchar(32) not null,
    planting_date   date not null,
    declared_area_ha numeric(14,4) not null,
    observed_at     timestamptz not null,
    ingested_at     timestamptz not null,
    revision_no     int not null default 1,
    revision_of_id  bigint references planting_declaration(id),
    superseded_by_id bigint references planting_declaration(id),
    status          varchar(16) not null default 'ACTIVE' check (status in ('ACTIVE','SUPERSEDED','REJECTED')),
    source          varchar(120),
    created_at      timestamptz not null default now(),
    unique (declaration_no, revision_no)
);

create index idx_decl_parcel on planting_declaration(parcel_id, ingested_at);

-- ---------------------------------------------------------------------------
-- Claims and scoring
-- ---------------------------------------------------------------------------

create table claim_case (
    id                  bigserial primary key,
    case_no             varchar(80) not null unique,
    policy_no           varchar(80) not null,
    parcel_id           bigint not null references parcel(id),
    crop_code           varchar(32),
    hazard_type         varchar(32) not null default 'DROUGHT',
    hazard_date         date not null,
    scoring_cutoff      timestamptz not null,
    boundary_version_id bigint not null references parcel_boundary_version(id),
    status              varchar(24) not null default 'OPEN'
                            check (status in ('OPEN','QUEUED','SCORED','UNDER_REVIEW','PAID')),
    settled_at          timestamptz,
    settled_by          varchar(120),
    created_at          timestamptz not null default now()
);

create index idx_case_parcel on claim_case(parcel_id);

-- Definitions frozen per case at intake. A later catalog approval cannot silently change the case.
create table claim_case_feature_lock (
    case_id                     bigint not null references claim_case(id) on delete cascade,
    feature_definition_version_id bigint not null references feature_definition_version(id),
    primary key (case_id, feature_definition_version_id)
);

create table feature_run (
    id                  bigserial primary key,
    case_id             bigint references claim_case(id),
    mode                varchar(24) not null check (mode in ('ORIGINAL','REPLAY','CONTROLLED_BACKFILL')),
    trigger_type        varchar(32) not null
                            check (trigger_type in ('CASE_SCORING','BACKFILL_REQUEST','IMPACT_ANALYSIS','REPRODUCTION')),
    status              varchar(24) not null default 'QUEUED'
                            check (status in ('QUEUED','RUNNING','SUCCEEDED','LEAKAGE_BLOCKED','INSUFFICIENT_DATA','FAILED')),
    verdict             varchar(24) not null default 'NOT_EVALUATED'
                            check (verdict in ('PASS','PASS_WITH_EXCLUSIONS','BLOCKED','CANDIDATE_ONLY','NOT_EVALUATED')),
    data_policy         varchar(24) not null default 'AS_OF_CUTOFF'
                            check (data_policy in ('AS_OF_CUTOFF','LATEST_AVAILABLE')),
    cutoff_at           timestamptz not null,
    availability_ceiling timestamptz not null,
    hazard_date         date,
    boundary_version_id bigint references parcel_boundary_version(id),
    boundary_variant    boolean not null default false,
    parent_run_id       bigint references feature_run(id),
    revision_event_id   bigint,
    reason              text,
    requested_by        varchar(120),
    requested_at        timestamptz not null default now(),
    started_at          timestamptz,
    finished_at         timestamptz,
    error_message       text,
    result_snapshot_id  bigint,
    result_candidate_id bigint,
    notes               text
);

create index idx_run_queue on feature_run(status, requested_at);
create index idx_run_case on feature_run(case_id, requested_at desc);

-- Exclusive pixel attribution: each scene cell belongs to exactly one field.
-- A cell that lies inside two overlapping boundary versions is granted to the
-- higher-priority claim (the locked case boundary outranks an alternative variant),
-- so the same pixel is never counted into two fields. Claims are re-evaluated
-- deterministically, which keeps replays reproducible regardless of run order.
create table pixel_claim (
    id                  bigserial primary key,
    scene_id            bigint not null references satellite_scene(id),
    cell_id             bigint not null references satellite_scene_cell(id),
    run_id              bigint references feature_run(id) on delete set null,
    case_id             bigint references claim_case(id),
    parcel_id           bigint not null references parcel(id),
    boundary_version_id bigint not null references parcel_boundary_version(id),
    ndvi                numeric(8,5) not null,
    claimed_at          timestamptz not null default now(),
    unique (scene_id, cell_id)
);

create index idx_pixel_claim_run on pixel_claim(run_id);
create index idx_pixel_claim_owner on pixel_claim(parcel_id, boundary_version_id);



create table feature_run_input (
    id              bigserial primary key,
    run_id          bigint not null references feature_run(id) on delete cascade,
    feature_codes   text,
    source_type     varchar(32) not null,
    source_table    varchar(64) not null,
    source_id       bigint not null,
    revision_no     int,
    observed_at     timestamptz,
    ingested_at     timestamptz,
    eligibility     varchar(48) not null,
    reason          text,
    contributed_value numeric(16,6),
    detail          jsonb
);

create index idx_run_input_run on feature_run_input(run_id);

create table feature_run_feature (
    id                      bigserial primary key,
    run_id                  bigint not null references feature_run(id) on delete cascade,
    feature_code            varchar(64) not null,
    definition_version_id   bigint not null references feature_definition_version(id),
    formula                 text not null,
    value                   numeric(18,6),
    unit                    varchar(32),
    status                  varchar(24) not null check (status in ('COMPUTED','NULL','INSUFFICIENT_DATA')),
    input_count             int not null default 0,
    window_start            timestamptz,
    window_end              timestamptz,
    detail                  jsonb,
    unique (run_id, feature_code)
);

create table leakage_report (
    id                  bigserial primary key,
    run_id              bigint not null unique references feature_run(id) on delete cascade,
    verdict             varchar(24) not null,
    cutoff_at           timestamptz not null,
    availability_ceiling timestamptz not null,
    input_count         int not null,
    eligible_count      int not null,
    excluded_count      int not null,
    blocked_count       int not null,
    checked_at          timestamptz not null default now(),
    summary             text
);

create table leakage_violation (
    id              bigserial primary key,
    report_id       bigint not null references leakage_report(id) on delete cascade,
    code            varchar(64) not null,
    severity        varchar(16) not null check (severity in ('INFO','WARNING','BLOCKING')),
    source_type     varchar(32),
    source_id       bigint,
    feature_code    varchar(64),
    message         text not null,
    detail          jsonb
);

create index idx_violation_report on leakage_violation(report_id);

create table scoring_snapshot (
    id                      bigserial primary key,
    case_id                 bigint not null references claim_case(id),
    run_id                  bigint not null references feature_run(id),
    boundary_version_id     bigint not null references parcel_boundary_version(id),
    scoring_cutoff          timestamptz not null,
    feature_values          jsonb not null,
    definition_versions     jsonb not null,
    inputs_hash             varchar(64) not null,
    supersedes_snapshot_id  bigint references scoring_snapshot(id),
    created_at              timestamptz not null default now()
);

create index idx_snapshot_case on scoring_snapshot(case_id, created_at desc);

create table snapshot_feature (
    id                      bigserial primary key,
    snapshot_id             bigint not null references scoring_snapshot(id) on delete cascade,
    feature_code            varchar(64) not null,
    definition_version_id   bigint not null references feature_definition_version(id),
    formula                 text not null,
    value                   numeric(18,6),
    unit                    varchar(32),
    status                  varchar(24) not null,
    evidence                jsonb,
    unique (snapshot_id, feature_code)
);

-- The exact input ledger captured in a snapshot, so a replay can be diffed against it.
create table snapshot_input (
    id              bigserial primary key,
    snapshot_id     bigint not null references scoring_snapshot(id) on delete cascade,
    source_type     varchar(32) not null,
    source_id       bigint not null,
    revision_no     int,
    observed_at     timestamptz,
    ingested_at     timestamptz,
    eligibility     varchar(48) not null,
    reason          text,
    detail          jsonb
);

create index idx_snapshot_input_snapshot on snapshot_input(snapshot_id);

-- A frozen snapshot is the legal record of what was known at decision time.
create or replace function block_snapshot_mutation() returns trigger
language plpgsql as $$
begin
    raise exception 'scoring snapshots are immutable (% on %)', tg_op, tg_table_name;
end;
$$;

create trigger trg_snapshot_immutable
    before update or delete on scoring_snapshot
    for each row execute function block_snapshot_mutation();

create trigger trg_snapshot_feature_immutable
    before update or delete on snapshot_feature
    for each row execute function block_snapshot_mutation();

create trigger trg_snapshot_input_immutable
    before update or delete on snapshot_input
    for each row execute function block_snapshot_mutation();

create table replay_verification (
    id              bigserial primary key,
    snapshot_id     bigint not null references scoring_snapshot(id),
    run_id          bigint not null references feature_run(id),
    values_match    boolean not null,
    diff            jsonb,
    created_at      timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Revisions, impact analysis and review candidates
-- ---------------------------------------------------------------------------

create table data_revision_event (
    id              bigserial primary key,
    source_type     varchar(32) not null,
    source_table    varchar(64) not null,
    source_id       bigint not null,
    external_key    varchar(100),
    revision_no     int not null,
    previous_id     bigint,
    parcel_id       bigint references parcel(id),
    observed_at     timestamptz,
    ingested_at     timestamptz not null,
    window_start    timestamptz,
    window_end      timestamptz,
    description     text,
    created_at      timestamptz not null default now()
);

create index idx_revision_event_ingest on data_revision_event(ingested_at desc);

create table impact_analysis (
    id                  bigserial primary key,
    revision_event_id   bigint not null references data_revision_event(id),
    status              varchar(24) not null default 'COMPLETED',
    affected_cases      int not null default 0,
    candidate_cases     int not null default 0,
    summary             jsonb,
    created_at          timestamptz not null default now()
);

create table impact_case (
    id                  bigserial primary key,
    analysis_id         bigint not null references impact_analysis(id) on delete cascade,
    case_id             bigint not null references claim_case(id),
    snapshot_id         bigint references scoring_snapshot(id),
    effect              varchar(40) not null
                            check (effect in ('NO_IMPACT','NOT_RELEVANT','ALREADY_VISIBLE_AT_CUTOFF','RECOMPUTE_CANDIDATE')),
    reason              text,
    cutoff_at           timestamptz,
    backfill_run_id     bigint references feature_run(id),
    candidate_id        bigint,
    unique (analysis_id, case_id)
);

create index idx_impact_case_analysis on impact_case(analysis_id);

create table review_candidate (
    id                  bigserial primary key,
    analysis_id         bigint references impact_analysis(id),
    case_id             bigint not null references claim_case(id),
    backfill_run_id     bigint references feature_run(id),
    snapshot_id         bigint references scoring_snapshot(id),
    status              varchar(24) not null default 'PENDING_REVIEW'
                            check (status in ('PENDING_REVIEW','ACKNOWLEDGED','DISMISSED')),
    reason              text,
    baseline_values     jsonb,
    candidate_values    jsonb,
    delta               jsonb,
    created_at          timestamptz not null default now(),
    decided_at          timestamptz,
    decided_by          varchar(120),
    decision_note       text,
    unique (analysis_id, case_id)
);

create index idx_candidate_status on review_candidate(status, created_at desc);

-- Guard rails: formula/definition versions are immutable once approved.
create or replace function block_approved_version_mutation() returns trigger
language plpgsql as $$
begin
    if old.status in ('APPROVED','SUPERSEDED','RETIRED') then
        if new.formula is distinct from old.formula
           or new.feature_definition_id is distinct from old.feature_definition_id
           or new.version_no is distinct from old.version_no
           or new.availability_lag_seconds is distinct from old.availability_lag_seconds
           or new.as_of_policy is distinct from old.as_of_policy then
            raise exception 'approved feature definition versions are immutable';
        end if;
    end if;
    return new;
end;
$$;

create trigger trg_fdv_guard
    before update on feature_definition_version
    for each row execute function block_approved_version_mutation();
