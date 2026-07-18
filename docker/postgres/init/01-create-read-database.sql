-- Runs once, on first initialisation of the Postgres data volume (the standard
-- /docker-entrypoint-initdb.d hook). Creates the read-side database so a fresh
-- `docker compose up` yields both databases with no manual step.
--
-- command-service owns `reckon` (event store); projection-service owns `reckon_read`
-- (read models). One Postgres container, two databases, zero shared tables -- the CQRS
-- boundary, enforced by the schema layout itself.
CREATE DATABASE reckon_read;
GRANT ALL PRIVILEGES ON DATABASE reckon_read TO reckon;
