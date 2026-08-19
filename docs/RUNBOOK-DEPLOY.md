# Production deploy runbook

SYSTEM 03 TASK 3.2. No production deployment exists yet (see `docs/INFRA-CURRENT.md` — OCI is the
confirmed target, not yet provisioned), so this is the procedure to follow once one does, not a
record of one already having happened. The mechanism it describes (Flyway decoupled from app boot,
a startup schema-version guard) is built and tested now regardless.

## Why this exists

Before this task, `spring.flyway.enabled=true` unconditionally — every application instance ran
pending migrations itself, at boot, automatically. That means a bad migration doesn't fail one
canary instance; it fails **every instance simultaneously**, at the worst possible moment (mid
rolling-deploy, potentially with traffic already shifting to new instances). Migrations are now an
explicit, separate, pre-deploy step.

## What changed

- `spring.flyway.enabled` now defaults to `${FLYWAY_ENABLED:true}` normally, but
  `application-prod.properties` (active via `SPRING_PROFILES_ACTIVE=prod`) overrides the default to
  `${FLYWAY_ENABLED:false}`. Local dev and CI are unaffected; a production-profile instance no
  longer runs migrations at boot unless `FLYWAY_ENABLED=true` is explicitly set (an escape hatch,
  not the normal path).
- `StartupSchemaVersionCheck` (`server/.../config/StartupSchemaVersionCheck.java`) runs on every
  boot regardless of that setting. It builds its own Flyway instance against the live datasource,
  logs the current schema version, and — critically — **refuses to start** (throws, non-zero exit)
  if any migration the build ships is not yet applied to the database. This is what makes disabling
  auto-migrate safe: an instance that comes up against a stale schema fails loudly and immediately
  instead of serving traffic against a schema it doesn't match.

## The deploy sequence

1. **Build** the artifact (CI: `server-ci.yml`'s `build-and-test` job; TASK 3.1's
   `migration-validate` job has already proven this exact migration set applies cleanly to an empty
   database and is idempotent on a second run — that's a pre-merge gate, not a repeat of this step).
2. **Run migrations as a one-shot step**, before touching any application instance:
   ```
   mvn -f server/pom.xml flyway:migrate
   ```
   Needs `DB_URL`/`DB_USER`/`DB_PASSWORD` in the environment (real OS env vars for this standalone
   Maven goal — see the plugin's own comment in `server/pom.xml`; spring-dotenv only applies inside
   a booted Spring context). Run this from a deploy host/CI runner with network access to the
   database, as a distinct job/step with its own pass/fail signal — don't fold it into the
   application deploy step, so a migration failure is visibly a migration failure.
3. **Confirm success** — the `flyway:migrate` step above exits non-zero on any failure; treat that
   as a hard stop. Do not proceed to step 4.
4. **THEN roll application instances.** Each instance's `StartupSchemaVersionCheck` re-confirms the
   schema it's booting against actually matches what it expects — belt-and-suspenders against step 2
   having been skipped, run against the wrong database, or a stale build being deployed after a
   newer migration was already applied elsewhere.

## One-time provisioning step this sequence assumes (do NOT skip on a first-ever deploy)

Two migrations require Postgres **superuser** to run at all (`CREATE EXTENSION` is not delegable
to a normal role for either): `V032` (`pgvector`, for Lucien's RAG search) and `V097`
(`pg_stat_statements`, SYSTEM 02). The app's own deploy role is deliberately **not** superuser (same
reasoning as `backup_agent` in `docs/RUNBOOK-DR.md`) — running step 2 above as that restricted role
against a truly empty database will fail at V032 with `permission denied to create extension
"vector"`.

Before the very first `flyway:migrate` run against a new, empty database:
1. Connect as a superuser and run `CREATE EXTENSION IF NOT EXISTS vector;` once.
2. For `pg_stat_statements`: set `shared_preload_libraries = 'pg_stat_statements'` in
   `postgresql.conf` and **restart** Postgres (not just reload — this setting needs a full restart)
   before first boot. Then a superuser runs `CREATE EXTENSION IF NOT EXISTS pg_stat_statements;`
   once. (`V097` itself tolerates being run by a non-superuser role — it catches
   `insufficient_privilege` and warns instead of failing the migration chain — but the extension
   genuinely won't collect anything until this step has actually happened. See `docs/DB-HOTSPOTS.md`.)

This is a real, empirically-confirmed requirement, not a hypothetical: the full migration chain was
run against a genuinely fresh scratch database while building this task, and failed at V032 without
this step. CI's `migration-validate` job (`server-ci.yml`) sidesteps needing this by using the same
`POSTGRES_USER: opstool`-as-bootstrap-superuser service-container pattern the existing
`build-and-test` job already uses — that does NOT reflect how a real, security-conscious production
Postgres should be provisioned, where the app's role stays deliberately restricted. Whoever runs
SYSTEM 05's OCI provisioning needs to do the real superuser step above as part of first-boot setup,
not rely on CI's simplified model.

## Rollback / partial-failure scenarios

Covered in full in `docs/RUNBOOK-DR.md` (SYSTEM 41) — "bad migration in production" is one of its
named recovery scenarios, including why `flyway undo` is not used here (no down-migrations, by
policy — see `docs/MIGRATION-POLICY.md`, SYSTEM 03 TASK 3.3).
