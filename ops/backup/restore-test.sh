#!/usr/bin/env bash
# Quarterly restore-test procedure (SYSTEM 41 TASK 41.3). Scripts the exact steps run
# and verified manually on 2026-08-18 against the local dev database -- see
# docs/RUNBOOK-DR.md for that run's recorded timings and results. Re-run this every
# quarter (and after any schema-affecting migration) against the LATEST backup, into
# an isolated database, never against a database anything else depends on.
#
# Requires: superuser Postgres credentials (pg_restore needs to create extensions --
# see docs/RUNBOOK-DR.md TASK 41.3 finding #1) and the app's PII_ENCRYPTION_KEY_BASE64
# for the decrypt check. Never run this against production without an explicit,
# separate isolated target -- RESTORE_DB_NAME must not equal PGDATABASE.
set -euo pipefail

: "${PGHOST:?}" "${PGPORT:?}" "${PGSUPERUSER:?}" "${PGSUPERPASSWORD:?}"
: "${SOURCE_DUMP:?}" "${RESTORE_DB_NAME:?}" "${APP_DB_ROLE:?}" "${PII_ENCRYPTION_KEY_BASE64:?}"

if [ "$RESTORE_DB_NAME" = "${PGDATABASE:-}" ]; then
  echo "REFUSING: RESTORE_DB_NAME must not equal the live database" >&2
  exit 1
fi

export PGPASSWORD="$PGSUPERPASSWORD"
PSQL=(psql -h "$PGHOST" -p "$PGPORT" -U "$PGSUPERUSER" -w)
PGRESTORE=(pg_restore -h "$PGHOST" -p "$PGPORT" -U "$PGSUPERUSER")

echo "[restore-test] recreating isolated database ${RESTORE_DB_NAME}"
"${PSQL[@]}" -d postgres -c "DROP DATABASE IF EXISTS ${RESTORE_DB_NAME};"
"${PSQL[@]}" -d postgres -c "CREATE DATABASE ${RESTORE_DB_NAME} OWNER ${APP_DB_ROLE};"

START=$(date +%s)
# Deliberately as superuser, not --role=<app role> -- pg_restore must create
# extensions (permission denied for non-superusers, e.g. pgvector) and validate FK
# constraints, which trips FORCE ROW LEVEL SECURITY under any non-bypass role.
"${PGRESTORE[@]}" -d "$RESTORE_DB_NAME" -j 4 "$SOURCE_DUMP"
END=$(date +%s)
echo "[restore-test] restore completed in $((END-START))s"

echo "[restore-test] verifying RLS + trigger coverage matches source shape"
"${PSQL[@]}" -d "$RESTORE_DB_NAME" -c "
  SELECT (SELECT count(*) FROM pg_class WHERE relforcerowsecurity) AS rls_forced_tables,
         (SELECT count(*) FROM pg_policy) AS policy_count,
         (SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
            WHERE tgname LIKE '%immutable%') AS immutable_triggers;"

echo "[restore-test] verifying an immutability trigger rejects UPDATE even for superuser"
set +e
"${PSQL[@]}" -d "$RESTORE_DB_NAME" -c "
  UPDATE unified_audit_events SET action = 'TAMPERED'
  WHERE id = (SELECT id FROM unified_audit_events LIMIT 1);" 2>&1 | grep -q "is immutable" \
  && echo "[restore-test] OK: immutability trigger held" \
  || { echo "[restore-test] FAIL: immutability trigger did not block the UPDATE" >&2; exit 1; }
set -e

echo "[restore-test] verifying encrypted PII is still decryptable with the current key"
CP="target/test-classes;target/classes;$(cd "$(dirname "$0")/../../server" && mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout)"
(cd "$(dirname "$0")/../../server" && java -cp "$CP" \
  com.recoverpro.server.security.encryption.DrRestoreDecryptCheck \
  "jdbc:postgresql://${PGHOST}:${PGPORT}/${RESTORE_DB_NAME}" "$PGSUPERUSER" "$PGSUPERPASSWORD" \
  "$PII_ENCRYPTION_KEY_BASE64")

echo "[restore-test] PASS -- record these results in docs/RUNBOOK-DR.md"
