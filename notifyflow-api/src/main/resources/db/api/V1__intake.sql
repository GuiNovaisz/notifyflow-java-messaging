create table notifications (
  id uuid primary key, recipient varchar(254) not null, channel varchar(10) not null check(channel='EMAIL'),
  subject varchar(200) not null, body text not null, status varchar(20) not null check(status='ACCEPTED'),
  created_at timestamptz not null, updated_at timestamptz not null
);
create table intake_keys (
  key varchar(128) primary key, payload text not null, notification_id uuid not null unique,
  constraint intake_notification_fk foreign key(notification_id) references notifications(id) deferrable initially deferred
);
create table outbox_events (
  id uuid primary key, event_id uuid not null unique, aggregate_id uuid not null references notifications(id),
  event_type varchar(100) not null, event_version integer not null check(event_version=1),
  occurred_at timestamptz not null, payload text not null, exchange_name varchar(100) not null,
  routing_key varchar(100) not null, category varchar(50) not null,
  status varchar(20) not null default 'PENDING' check(status in ('PENDING','PUBLISHED')),
  attempts integer not null default 0 check(attempts>=0), next_attempt_at timestamptz not null default now(), published_at timestamptz,
  check((status='PUBLISHED')=(published_at is not null))
);
create index outbox_pending on outbox_events(next_attempt_at,occurred_at) where published_at is null;
