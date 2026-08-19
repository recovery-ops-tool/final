#!/usr/bin/env bash
# WAL archive_command target -- Postgres invokes this once per completed WAL segment,
# giving continuous point-in-time recovery between nightly pg_backup.sh dumps (which
# alone only give restore-to-last-midnight, not an RPO measured in minutes).
#
# DESIGN STATUS: written to standard Postgres archive_command practice, but NOT yet
# exercised against a running instance with archive_mode=on -- this repo has no
# Docker/live Postgres-in-container environment available to test it in (see
# docs/RUNBOOK-DR.md's TASK 41.2 notes). Verify end to end during OCI provisioning
# (SYSTEM 05) before relying on it: enable archiving, generate WAL traffic, confirm
# segments land in the bucket, then prove a PITR restore using them.
#
# Wired via postgresql.conf (or docker-compose `command:` -c flags):
#   archive_mode = on
#   archive_command = '/backup-scripts/pg-wal-archive.sh %p %f'
#   wal_level = replica
#
# Postgres requires archive_command to return zero ONLY on confirmed durable storage
# of the segment -- a non-zero exit tells Postgres to retry, so failures must not be
# swallowed.
set -euo pipefail

WAL_PATH="$1"   # %p -- path to the WAL file to archive
WAL_FILE="$2"   # %f -- just the filename

: "${BACKUP_S3_BUCKET:?}" "${BACKUP_S3_ENDPOINT:?}"

aws s3 cp "$WAL_PATH" "s3://${BACKUP_S3_BUCKET}/wal/${WAL_FILE}" \
  --endpoint-url "$BACKUP_S3_ENDPOINT"
