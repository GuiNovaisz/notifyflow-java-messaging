create table deliveries (
 id uuid primary key, event_id uuid not null unique, notification_id uuid not null unique,
 status varchar(20) not null check(status in ('PENDING','RETRYING','DELIVERED','DEAD_LETTERED')),
 attempt_count integer not null check(attempt_count between 0 and 4), updated_at timestamptz not null
);
create table delivery_attempts (
 delivery_id uuid not null references deliveries(id), attempt_number integer not null check(attempt_number between 1 and 4),
 started_at timestamptz not null, finished_at timestamptz not null, outcome varchar(20) not null,
 failure_category varchar(50) not null, provider_status integer, next_retry_at timestamptz,
 primary key(delivery_id,attempt_number)
);
create table outbox_events (
 id uuid primary key,event_id uuid not null,aggregate_id uuid not null,event_type varchar(100) not null,
 event_version integer not null check(event_version=1),occurred_at timestamptz not null,payload text not null,
 exchange_name varchar(100) not null,routing_key varchar(100) not null,category varchar(50) not null,
 status varchar(20) not null default 'PENDING' check(status in ('PENDING','PUBLISHED')),
 attempts integer not null default 0 check(attempts>=0), next_attempt_at timestamptz not null default now(),published_at timestamptz,
 check((status='PUBLISHED')=(published_at is not null))
);
create index outbox_pending on outbox_events(next_attempt_at,occurred_at) where published_at is null;
