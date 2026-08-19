# SYSTEM 03 — Database Migration System: execution record

Executed from `docs/PRODUCTION-TASKLIST.txt`, SYSTEM 03 block, 2026-08-18 session (continuation of
the same session that completed SYSTEM 41, SYSTEM 01, SYSTEM 02).

## Prerequisite check

None.

## TASK 3.1 — CI migration validation [DONE]

Added a `migration-validate` job to `.github/workflows/server-ci.yml` (a pre-existing `server-ci`
workflow already ran `mvn test` against a real Postgres 16 + Redis service; this is a new,
separate job so a migration failure and an application-test failure are distinguishable CI
signals): fresh Postgres container → `flyway:migrate` → `flyway:validate` → `flyway:migrate` again
(idempotency check).

Needed a `flyway-maven-plugin` declaration in `server/pom.xml` (pinned to `10.20.1`, matching the
exact `flyway-core` version already resolved, so CI validation can't drift from what the app
actually runs) — none existed before.

**Real bug found and fixed by actually running this, not just wiring the job**: a genuinely fresh
`flyway:migrate` (empty database, V001 through V097 in order) failed at V058 — `column
"organization_id" does not exist` on `lucien_chat_sessions`. Investigated: neither the original
`friday_chat_sessions` (V001) nor its rename (V003) ever had that column, and no migration between
V003 and V058 added it. The column only existed on this session's persistent dev database through
undocumented manual drift, not any tracked migration — meaning no genuinely fresh environment (a
new CI run, a real fresh OCI bootstrap) could have ever completed migrating. Confirmed with the
user before fixing (editing an already-applied migration has real operational cost — see below),
then fixed V058 directly: added `ADD COLUMN IF NOT EXISTS organization_id UUID` for both
`lucien_chat_sessions` and `lucien_chat_messages`, right before the RLS-enabling statements that
depend on it.

Editing an already-applied migration required `flyway repair` on this session's own dev database
(Flyway checksums every applied migration) — run and verified (`flyway:validate` clean afterward).
Re-ran the full scratch-database migration end to end after the fix: clean on both the first pass
and the idempotency-check second pass.

Also found, separately: the full chain cannot be applied by a genuinely restricted, non-superuser
role from empty (two migrations need `CREATE EXTENSION` privilege — V032 `pgvector`, V097
`pg_stat_statements`). Not a bug in the CI job itself (CI's `POSTGRES_USER: opstool` bootstraps
that role AS the container's actual superuser, same as the pre-existing `build-and-test` job), but
a real production-provisioning requirement — documented in `docs/RUNBOOK-DEPLOY.md`.

## TASK 3.2 — Decouple migration from application boot [DONE]

- **3.2.a**: `spring.flyway.enabled` is now `${FLYWAY_ENABLED:true}` in `application.properties`,
  overridden to `${FLYWAY_ENABLED:false}` in `application-prod.properties` (an existing prod-profile
  file, previously only used for disabling Swagger). Local/CI unaffected; a `prod`-profile instance
  no longer auto-migrates at boot.
- **3.2.b**: `docs/RUNBOOK-DEPLOY.md` — the full sequence (build → migrate as an explicit step →
  confirm → roll instances), plus the superuser extension-provisioning requirement TASK 3.1
  surfaced.
- **3.2.c**: `StartupSchemaVersionCheck` — builds its own `Flyway` instance (not Spring Boot's
  auto-configured one, since that bean's behavior itself depends on the setting this check needs to
  work regardless of), logs the current schema version, throws `IllegalStateException` naming every
  pending migration if the database is behind what the build ships.

  **A real regression found and fixed before it shipped**: this broke the pre-existing
  `ServerApplicationTest` (`@ActiveProfiles("local")`, H2 in-memory, `ddl-auto=create-drop` —
  deliberately never Flyway-managed at all) — the check correctly-by-its-own-logic saw 97 pending
  migrations against an empty H2 database and refused to start it. Fixed with `@Profile("!local")`
  rather than weakening the check itself.

  `StartupSchemaVersionCheckTest`: real integration test against a genuinely separate throwaway
  database (not a schema-within-the-same-database — that approach hit a real, if
  harmless-in-production, quirk in V003's un-schema-qualified `information_schema.tables` lookup;
  documented in the test's own javadoc rather than chased further). Proves both directions: a
  database deliberately left at V090 refuses to start; a fully-migrated one starts cleanly. Needs
  `CREATE DATABASE` privilege this session's local dev role deliberately doesn't have, so it skips
  (not fails) locally without exported OS env vars for a privileged role — verified for real by
  running it once with superuser credentials (2/2 pass) before confirming the skip path is correct.

## TASK 3.3 — Destructive-change policy [DONE]

`docs/MIGRATION-POLICY.md` — expand/contract discipline, DROP COLUMN/TABLE must name the migration
that made it obsolete, the `CREATE INDEX CONCURRENTLY` non-transactional-migration caveat (SYSTEM
02), and migration immutability (using this task's own V058 fix as the concrete example of what
"immutable, with a documented exception" actually looks like in practice). Repeated as a header
comment on `V097__pg_stat_statements.sql` (the most recent file at the time) for in-place
discoverability. Referenced from a new root `README.md` — neither `CLAUDE.md` nor any `README.md`
existed anywhere in the repo before this task, so a minimal one was created rather than skipping
the acceptance criterion.

## Verification

`mvn -f server/pom.xml flyway:validate` (SYSTEM 03's own SYSTEM VERIFICATION command) — clean,
after the V058/V097 checksum repairs.

Full `mvn -f server/pom.xml test`: **635 tests, 0 failures, 0 errors, 2 skipped (the two
`StartupSchemaVersionCheckTest` cases, which need CREATE DATABASE privilege not present locally —
verified separately with superuser credentials, 2/2 pass), BUILD SUCCESS** (up from 633 at the end
of SYSTEM 02: +2 `StartupSchemaVersionCheckTest`).
