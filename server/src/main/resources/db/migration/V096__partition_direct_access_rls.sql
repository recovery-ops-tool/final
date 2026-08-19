-- SYSTEM 01 TASK 1.2: RLS on a partitioned table's PARENT does not protect the partitions when
-- queried directly by name. Empirically proven before writing this migration (not assumed): with
-- app.current_org_id set to org B, `SELECT * FROM unified_audit_events` (the parent) correctly
-- returned zero of org A's rows, but `SELECT * FROM unified_audit_events_2026_08` (one of its
-- partitions, named directly) returned org A's row anyway. Postgres does not propagate a
-- partitioned table's row-security policies onto its partitions automatically for direct access --
-- each partition needs its own ENABLE/FORCE ROW LEVEL SECURITY and its own copy of the policy.
--
-- Scope note: unified_audit_events is fixed here because it already has a real parent-level policy
-- to mirror. user_action_audit_logs (this repo's other RANGE-partitioned audit table, tracked
-- alongside this one in PartitionMaintenanceJob.PARTITIONED_TABLES) was found during this same
-- investigation to have NO row-level security at all, on its parent or any partition -- it carries
-- no organization_id/org_id column, so there is no existing policy to mirror here, and designing
-- one (denormalized org_id column, or a join-to-users USING clause) is a bigger decision than this
-- task's "verify partitioned-table policy inheritance" scope. Left as a separate, real, flagged gap
-- -- see docs/RUNBOOK-DR.md's SYSTEM 01 notes -- not fixed in this migration.

DO $$
DECLARE
    partition_name text;
BEGIN
    FOR partition_name IN
        SELECT c.relname
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        WHERE i.inhparent = 'public.unified_audit_events'::regclass
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', partition_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', partition_name);
        IF NOT EXISTS (
            SELECT 1 FROM pg_policy WHERE polrelid = ('public.' || partition_name)::regclass
        ) THEN
            EXECUTE format(
                'CREATE POLICY rls_unified_audit_events_isolation ON %I
                   USING (organization_id = current_org_id()
                          OR current_setting(''app.is_platform_admin'', true) = ''true'')',
                partition_name);
        END IF;
    END LOOP;
END $$;
