#!/usr/bin/env bash
# Nightly logical backup of the RecoverPro Postgres database.
#
# This is the exact mechanism verified in a real restore test on 2026-08-18 (see
# docs/RUNBOOK-DR.md, SYSTEM 41 TASK 41.3): pg_dump custom-format via a dedicated
# backup_agent role, restored with pg_restore. What's new here versus that manual
# test is scheduling, upload, and retention -- the dump/restore mechanics themselves
# are already proven.
#
# Requires:
#   - Postgres role `backup_agent`: LOGIN, BYPASSRLS, granted pg_read_all_data.
#     Deliberately NOT the app's runtime role (opstool) -- FORCE ROW LEVEL SECURITY
#     blocks even the table owner, and a compromised app connection must not be able
#     to bypass RLS just because a backup credential happens to share it.
#   - AWS CLI (`aws`) configured to talk to the object storage endpoint. OCI Object
#     Storage exposes an S3-compatible API, so `aws s3 cp --endpoint-url` works
#     unmodified -- no OCI-specific tooling needed.
#
# Env vars (all required, no silent defaults -- see docs/CONFIG-REFERENCE.md):
#   PGHOST, PGPORT, PGDATABASE, PGUSER (=backup_agent), PGPASSWORD
#   BACKUP_S3_BUCKET, BACKUP_S3_ENDPOINT      (OCI Object Storage bucket + endpoint)
#   BACKUP_RETENTION_DAYS                     (nightly dumps older than this are pruned)
set -euo pipefail

: "${PGHOST:?}" "${PGPORT:?}" "${PGDATABASE:?}" "${PGUSER:?}" "${PGPASSWORD:?}"
: "${BACKUP_S3_BUCKET:?}" "${BACKUP_S3_ENDPOINT:?}" "${BACKUP_RETENTION_DAYS:?}"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
WORKDIR="$(mktemp -d)"
DUMPFILE="${WORKDIR}/${PGDATABASE}-${STAMP}.dump"
trap 'rm -rf "$WORKDIR"' EXIT

echo "[pg-backup] starting dump of ${PGDATABASE} at ${STAMP}"
pg_dump -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -F c -f "$DUMPFILE"

SIZE_BYTES=$(stat -c%s "$DUMPFILE" 2>/dev/null || stat -f%z "$DUMPFILE")
echo "[pg-backup] dump complete: ${SIZE_BYTES} bytes"

DEST="s3://${BACKUP_S3_BUCKET}/postgres/${PGDATABASE}-${STAMP}.dump"
aws s3 cp "$DUMPFILE" "$DEST" --endpoint-url "$BACKUP_S3_ENDPOINT"
echo "[pg-backup] uploaded to ${DEST}"

# Prune dumps older than the retention window. List, filter by embedded timestamp,
# delete -- never a bare `rm -rf` equivalent against the whole prefix.
CUTOFF_EPOCH=$(date -u -d "-${BACKUP_RETENTION_DAYS} days" +%s 2>/dev/null \
  || date -u -v-"${BACKUP_RETENTION_DAYS}"d +%s)
aws s3 ls "s3://${BACKUP_S3_BUCKET}/postgres/" --endpoint-url "$BACKUP_S3_ENDPOINT" \
  | awk '{print $4}' | grep -E "^${PGDATABASE}-[0-9]{8}T[0-9]{6}Z\.dump$" \
  | while read -r name; do
      ts="${name#"${PGDATABASE}"-}"
      ts="${ts%.dump}"
      file_epoch=$(date -u -d "${ts:0:8} ${ts:9:2}:${ts:11:2}:${ts:13:2}" +%s 2>/dev/null \
        || date -u -j -f "%Y%m%dT%H%M%SZ" "$ts" +%s)
      if [ "$file_epoch" -lt "$CUTOFF_EPOCH" ]; then
        echo "[pg-backup] pruning ${name} (past ${BACKUP_RETENTION_DAYS}-day retention)"
        aws s3 rm "s3://${BACKUP_S3_BUCKET}/postgres/${name}" --endpoint-url "$BACKUP_S3_ENDPOINT"
      fi
    done

echo "[pg-backup] done"
