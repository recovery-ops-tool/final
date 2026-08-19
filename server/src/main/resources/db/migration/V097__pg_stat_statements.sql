-- ============================================================================================
-- MIGRATION POLICY (SYSTEM 03 TASK 3.3, full version: docs/MIGRATION-POLICY.md) -- applies to
-- every file in this directory, repeated here for in-place discoverability:
--   1. Expand/contract, never both in one migration: add new structure and let the app switch
--      over first; a DROP is always a separate, later migration.
--   2. Every DROP COLUMN/DROP TABLE names the earlier migration/version that made it obsolete.
--   3. CREATE INDEX CONCURRENTLY needs a non-transactional migration (Flyway can't run it inside
--      a transaction) -- or accept the blocking lock during a documented maintenance window and
--      say which was chosen. Never add a speculative index -- trace it to docs/DB-HOTSPOTS.md.
--   4. Migrations are immutable once applied -- editing one breaks flyway:validate everywhere it
--      already ran and needs an explicit `flyway repair` on each (see V058's header for a real
--      example of when this was actually necessary).
-- ============================================================================================

-- SYSTEM 02 TASK 2.1: enables pg_stat_statements so query-level hotspots can be identified from
-- real evidence (docs/DB-HOTSPOTS.md) instead of guesswork, per TASK 2.2's own "do not add
-- speculative indexes" rule.
--
-- TWO THINGS THIS MIGRATION CANNOT DO BY ITSELF, both confirmed by hitting them directly while
-- writing it (not assumed):
--
-- 1. shared_preload_libraries. pg_stat_statements needs its shared library loaded into the
--    postmaster's shared memory at server START (`shared_preload_libraries = 'pg_stat_statements'`
--    in postgresql.conf), which needs a full restart, not just a reload -- no SQL migration can do
--    that. Without it, CREATE EXTENSION below still succeeds (the SQL objects exist) but the view
--    stays permanently empty -- the collection hooks were never loaded.
-- 2. Superuser. CREATE EXTENSION pg_stat_statements is NOT a "trusted" extension (unlike e.g.
--    pg_trgm) -- confirmed empirically: running it as this app's normal migration role (opstool,
--    intentionally non-superuser, same reasoning as SYSTEM 41's separate backup_agent role) fails
--    with "permission denied ... Must be superuser to create this extension." Since Flyway
--    connects as that same restricted role in every environment, a bare CREATE EXTENSION here
--    would fail EVERY deploy, not just ones missing step 1.
--
-- So: this migration only ATTEMPTS the extension creation, and treats "insufficient privilege" as
-- a warning, not a failure -- it does not block the rest of the migration chain on infrastructure
-- a DBA/superuser has to provision out-of-band. The actual required steps (set
-- shared_preload_libraries, restart, then have a superuser run CREATE EXTENSION once) are the
-- deployment runbook's job -- documented in docs/DB-HOTSPOTS.md, which also carries the
-- 2026-08-18 local verification that this whole sequence, done correctly with superuser access,
-- produces a working pg_stat_statements with real, harvested numbers.
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
    -- Lets an operator role read the stats without needing full superuser for the harvest query
    -- in docs/DB-HOTSPOTS.md -- narrowest privilege that does the job, same principle as SYSTEM
    -- 41's backup_agent role.
    EXECUTE format('GRANT pg_read_all_stats TO %I', current_user);
EXCEPTION
    WHEN insufficient_privilege THEN
        RAISE WARNING 'pg_stat_statements NOT enabled: this migration role lacks superuser. '
            'A superuser must run CREATE EXTENSION pg_stat_statements manually (after setting '
            'shared_preload_libraries and restarting Postgres -- see docs/DB-HOTSPOTS.md) before '
            'the hotspot-harvest query in that file will return anything.';
END $$;
