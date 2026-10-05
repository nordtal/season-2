CREATE TABLE widgets
(
    id    BIGSERIAL PRIMARY KEY,
    name  TEXT        NOT NULL UNIQUE,
    owner UUID        NOT NULL
);
