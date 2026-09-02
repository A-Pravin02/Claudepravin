-- Runs after 00-roles.sql, as superuser, against POSTGRES_DB.
--
-- The postgres image creates POSTGRES_DB owned by the superuser. Migrations
-- run as aea_owner and begin with CREATE EXTENSION, which requires the
-- DATABASE-level CREATE privilege -- schema grants alone are not enough.
-- Getting this wrong is what broke the first CI run; the failure mode is
-- "permission denied to create extension" on the very first migration.
ALTER DATABASE aea OWNER TO aea_owner;
GRANT ALL ON SCHEMA public TO aea_owner;
