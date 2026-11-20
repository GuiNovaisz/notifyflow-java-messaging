create table provider_requests(key varchar(128) primary key,payload text not null,calls integer not null check(calls>=0));
create table provider_effects(id uuid primary key,key varchar(128) not null unique references provider_requests(key),created_at timestamptz not null);
