create table if not exists users
(
    id            integer primary key autoincrement,
    user_account  text                                not null unique,
    user_password text                                not null,
    user_name     text                                not null,
    user_avatar   text      default ''                not null,
    user_role     text      default 'user'            not null,
    created_at    timestamp default current_timestamp not null,
    updated_at    timestamp default current_timestamp not null,
    is_deleted    integer   default 0                 not null
);

create index if not exists idx_users_user_name on users (user_name);
create index if not exists idx_users_user_role on users (user_role);

create table if not exists sessions
(
    token      text primary key,
    user_id    integer                             not null,
    created_at timestamp default current_timestamp not null,
    foreign key (user_id) references users (id) on delete cascade
);
