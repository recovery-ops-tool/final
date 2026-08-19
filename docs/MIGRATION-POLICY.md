# Database migration policy

SYSTEM 03 TASK 3.3. Applies to every file in `server/src/main/resources/db/migration/`.

## Expand/contract, not add-and-destroy in one step

A migration that both adds new structure AND removes old structure in the same file forces
application code to switch over atomically with the schema change — impossible with more than one
running instance (rolling deploys, SYSTEM 05), and impossible to roll back a bad app deploy without
also reverting the schema.

Instead:
1. **Expand**: add the new column/table/index in one migration. Old code ignores it; new code can
   start using it. Deploy the app change that uses it.
2. **Contract**: once the app change is deployed and confirmed (no old code paths reading the old
   structure), a **later, separate** migration removes what's no longer used.

Never combine these two steps. If a task's own migration reads like "add X, immediately drop Y in
the same file," split it into two migrations across two deploys.

## Every DROP COLUMN / DROP TABLE names the migration that stopped using it

A destructive migration's header comment must name which earlier migration/version made the
dropped structure obsolete, so a future reader can trace *why* it's safe to remove without having
to reconstruct app history from git blame. Example:

```sql
-- V123__drop_legacy_status_column.sql
-- allocations.legacy_status was superseded by allocations.status in V087 (deployed 2026-06-01).
-- No code has read legacy_status since; confirmed via grep across the codebase before writing
-- this migration.
ALTER TABLE allocations DROP COLUMN legacy_status;
```

## `CREATE INDEX CONCURRENTLY` and non-transactional migrations

Flyway runs each migration in a transaction by default; `CREATE INDEX CONCURRENTLY` cannot run
inside one (Postgres forbids it). For an index migration on a table large enough that a normal
`CREATE INDEX` would hold a blocking lock unacceptably long (SYSTEM 02 TASK 2.2's own threshold:
identified via `docs/DB-HOTSPOTS.md` evidence, not guessed), either:

- Mark that specific migration non-transactional (Flyway: `-- flyway:executeInTransaction=false` as
  the first line of the SQL file for Community Edition's per-script transaction control), so
  `CONCURRENTLY` can run, **or**
- Accept the blocking lock deliberately, during an explicit, documented maintenance window, and say
  so in the migration's header comment (which approach was chosen and why).

Do not add speculative indexes at all — see `docs/DB-HOTSPOTS.md`'s own rule, inherited from SYSTEM
02 TASK 2.2: every index must trace to real evidence, not a guess.

## Migrations are immutable once applied

Flyway checksums every applied migration and validates it on every subsequent run. Editing an
already-applied migration file breaks `flyway:validate` for every environment that already ran it
— confirmed directly during this task, fixing a real bug in `V058` (a truly fresh `flyway:migrate`
failed because two tables it enables RLS on never actually had the `organization_id` column any
migration added — see that file's own header comment for the full story) required `flyway repair`
on every environment that had already applied it, and is not something to do lightly or without
recording it clearly in that migration's own header. If a shipped migration turns out to be wrong,
the default fix is a **new, later** migration that corrects it — editing history is the rare
exception, not the normal path, and always needs a repair step called out explicitly.

## Discoverability

The same rules are repeated as a comment block at the top of this directory's most recent
migration file at time of writing (`V097__pg_stat_statements.sql`) so they're visible in-place to
anyone opening the migrations folder, not just in `docs/`.
