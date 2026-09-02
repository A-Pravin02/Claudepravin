#!/bin/bash
# SessionStart hook for Claude Code on the web.
#
# Containers are ephemeral: each session starts with PostgreSQL stopped and no
# databases. The RLS test suite connects to a real PostgreSQL on purpose (so
# nothing in the framework can be credited with enforcing tenant isolation),
# which means the database must exist before `mvn verify` can run at all.
#
# Idempotent and non-interactive. Safe to re-run.
set -euo pipefail

# Local machines manage their own database; only provision the web sandbox.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$PROJECT_DIR"

echo "==> Starting PostgreSQL"
service postgresql start >/dev/null 2>&1 || true
for _ in $(seq 1 30); do
  pg_isready -q && break
  sleep 1
done
pg_isready || { echo "PostgreSQL failed to start"; exit 1; }

echo "==> Provisioning roles (aea_owner, aea_app)"
su postgres -c "psql -q -v ON_ERROR_STOP=1 -d postgres -f '$PROJECT_DIR/docker/postgres/init/00-roles.sql'"

# aea_owner must OWN each database, not merely hold schema rights:
# CREATE EXTENSION requires the database-level CREATE privilege.
for db in aea aea_test; do
  echo "==> Ensuring database $db"
  exists=$(su postgres -c "psql -tAc \"SELECT 1 FROM pg_database WHERE datname='$db'\"")
  if [ "$exists" != "1" ]; then
    su postgres -c "psql -q -v ON_ERROR_STOP=1 -c \"CREATE DATABASE $db OWNER aea_owner;\""
  else
    su postgres -c "psql -q -v ON_ERROR_STOP=1 -c \"ALTER DATABASE $db OWNER TO aea_owner;\""
  fi
  su postgres -c "psql -q -v ON_ERROR_STOP=1 -d $db -c 'GRANT ALL ON SCHEMA public TO aea_owner;'"
done

# Fail loudly rather than let the RLS suite pass vacuously against an
# over-privileged runtime role.
echo "==> Asserting aea_app is not privileged"
su postgres -c "psql -tAc \"SELECT CASE WHEN rolbypassrls OR rolsuper OR rolcreatedb
                                        THEN 'FAIL' ELSE 'ok' END
                            FROM pg_roles WHERE rolname='aea_app'\"" | grep -qx ok \
  || { echo "aea_app is over-privileged; RLS tests would pass vacuously"; exit 1; }

echo "==> Warming Maven dependencies"
(cd apps/api && mvn -B -q dependency:go-offline) || echo "    (partial; build will fetch the rest)"

echo "==> Installing web dependencies"
(cd apps/web && npm install --no-audit --no-fund --silent) || echo "    (npm install failed; run manually)"

echo "==> Ready. 'cd apps/api && mvn -B verify' should pass."
