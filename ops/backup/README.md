# Backup & restore — deployment wiring

Full context, the RPO/RTO decision, and the 2026-08-18 real restore-test results live in
`docs/RUNBOOK-DR.md` (SYSTEM 41). This file is just "how do these pieces get installed."

## What's here

| File | Runs where | Status |
|---|---|---|
| `postgres.Dockerfile` | builds the `postgres` compose service | untested (no live/Docker env this session) |
| `pg-wal-archive.sh` | inside the postgres container, invoked by Postgres itself per WAL segment | untested — see file header |
| `pg-backup.sh` | inside the postgres container, invoked nightly via systemd timer on the host | **mechanism proven** — same pg_dump/backup_agent pattern verified live 2026-08-18 |
| `restore-test.sh` | a separate restore-drill host/workstation with psql, pg_restore, and this repo checked out | **the exact procedure run and verified live 2026-08-18** |
| `recoverpro-backup.{service,timer}` | systemd units on the OCI host | untested — install steps below |
| `.env.example` | template — copy to `.env` (gitignored) with real values | — |

## One-time setup (do this during SYSTEM 05's OCI provisioning, before first prod deploy)

1. Create the dedicated backup role. Same DB, same pattern used in the local restore
   test — see `docs/RUNBOOK-DR.md` TASK 41.1 for why it can't just be `opstool`:
   ```sql
   CREATE ROLE backup_agent WITH LOGIN PASSWORD '<generate a real secret>'
     BYPASSRLS NOSUPERUSER NOCREATEDB NOCREATEROLE;
   GRANT pg_read_all_data TO backup_agent;
   ```
2. Create an OCI Object Storage bucket for backups, versioning enabled, **separate**
   from any bucket the app itself writes to via `aws.s3.endpoint-override`
   (`server/src/main/resources/application.properties`) — a backup store the running
   app can also write/delete from isn't an independent copy.
3. Create an OCI Object Storage-scoped access key for that bucket only, with delete
   permission granted to the backup process but not to the app's own S3 credential.
4. `cp ops/backup/.env.example ops/backup/.env` and fill in the bucket, endpoint,
   credentials, and `backup_agent`'s password.
5. `docker compose up -d --build postgres` to rebuild the postgres image with the AWS
   CLI and pick up the archive_command wiring from `docker-compose.yml`.
6. Install the systemd timer:
   ```
   sudo cp ops/backup/recoverpro-backup.{service,timer} /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now recoverpro-backup.timer
   ```
7. **Do not leave step 4 undone once step 5 has run.** `archive_mode=on` is unconditional in
   `docker-compose.yml` -- if `ops/backup/.env` is missing or has an empty bucket/endpoint,
   `archive_command` fails on every WAL segment. Postgres does not disable WAL retention when
   archiving fails; it keeps every unarchived segment in `pg_wal/` and retries forever, which will
   eventually fill the disk. Either configure `.env` before starting `postgres`, or set
   `archive_mode=off` in `docker-compose.yml` until you're ready to configure it.
8. **Before trusting any of this**: run `ops/backup/restore-test.sh` once by hand
   (see its header for required env vars) and confirm it reports PASS. Then trigger
   `systemctl start recoverpro-backup.service` once manually and confirm a dump lands
   in the bucket. Neither archive_mode nor the systemd timer has been exercised
   against a real running instance as of this writing — SYSTEM 05 provisioning is
   the first opportunity to actually do that.

## Quarterly restore test (TASK 41.3)

Run `ops/backup/restore-test.sh` against the latest backup, into a throwaway
database. Record the timing and pass/fail in `docs/RUNBOOK-DR.md`'s restore-test log.
Never point `RESTORE_DB_NAME` at anything else depends on — the script refuses to run
if it matches `PGDATABASE`.
