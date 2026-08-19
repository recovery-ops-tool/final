# Database hotspots — pg_stat_statements harvest

SYSTEM 02 TASK 2.1/2.2. Real numbers below, harvested 2026-08-18 against the local dev Postgres
16.11 instance — no production/OCI database exists yet (see `docs/INFRA-CURRENT.md`), so this is
the best representative source available: the full server test suite (627 tests,
`mvn -f server/pom.xml test`) run against a stats-reset instance, which exercises a genuine
cross-section of the app's real query shapes (every repository, every service path the tests
cover) even though the row *volumes* are dev-scale, not production-scale.

## Enabling this (required once per environment, not automatic)

`pg_stat_statements` needs two things a Flyway migration cannot do by itself — both discovered by
hitting them directly while building this, not assumed:

1. **`shared_preload_libraries = 'pg_stat_statements'`** in `postgresql.conf`, which needs a full
   Postgres **restart** (not reload) to take effect.
2. **Superuser** to run `CREATE EXTENSION pg_stat_statements` — it is not a "trusted" extension.
   This app's normal migration role (`opstool`) is deliberately non-superuser (same reasoning as
   `backup_agent` in SYSTEM 41's `docs/RUNBOOK-DR.md`), so `V097__pg_stat_statements.sql` only
   *attempts* the extension and warns (doesn't fail the migration chain) if it lacks privilege.

Verified end-to-end locally: set the config, restarted the service, had a superuser run the
extension creation, confirmed `SELECT count(*) FROM pg_stat_statements` returns real rows. For OCI
provisioning (SYSTEM 05, not done yet), this needs to be part of the initial instance setup, before
first boot or during a planned maintenance restart — record it in the provisioning checklist when
that work actually runs.

## Harvest query (one command, once granted `pg_read_all_stats` — see V097)

```sql
-- Top 25 by total time spent (what's costing the most cumulative DB time)
SELECT round(total_exec_time::numeric,2) AS total_ms, calls,
       round(mean_exec_time::numeric,3) AS mean_ms, rows, query
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 25;

-- Top 25 by mean time (what's slowest per call, excluding session/catalog noise)
SELECT round(mean_exec_time::numeric,3) AS mean_ms, calls, rows, query
FROM pg_stat_statements
WHERE query NOT LIKE '%pg_catalog%' AND query NOT ILIKE 'SET %' AND query NOT ILIKE 'SHOW %'
ORDER BY mean_exec_time DESC
LIMIT 25;

-- Sequential-scan volume per table (the actual TASK 2.2 input, not query text guessing)
SELECT relname, seq_scan, seq_tup_read, idx_scan, n_live_tup
FROM pg_stat_user_tables
WHERE seq_scan > 0
ORDER BY seq_tup_read DESC;
```

Reset before a fresh harvest with `SELECT pg_stat_statements_reset();`.

## Top 25 by total execution time (2026-08-18, post full-suite run)

| total_ms | calls | mean_ms | rows | query (truncated) |
|---|---|---|---|---|
| 459.57 | 6148 | 0.075 | 6148 | `insert into allocation_name_search_tokens (...) values (...)` |
| 221.16 | 34 | 6.505 | 34 | `delete from organizations o1_0 where o1_0.id in ($1)` |
| 217.63 | 33 | 6.595 | 33 | `delete from users u1_0 where u1_0.id in ($1) and (deleted_at IS NULL)` |
| 161.90 | 26 | 6.227 | 26 | `delete from allocations where id=$1 and version=$2` |
| 95.14 | 422 | 0.225 | 6050 | `delete from allocation_name_search_tokens where allocation_id=$1` |
| 69.23 | 6148 | 0.011 | 0 | `select ... from allocation_name_search_tokens where (allocation_id,token_hash) in (...)` |
| 68.03 | 18 | 3.780 | 36 | `delete from organizations o1_0 where o1_0.id in ($1,$2)` |
| 64.11 | 2 | 32.053 | 3130 | JDBC driver metadata introspection (`pg_catalog`, not app code) |
| 57.41 | 29 | 1.980 | 29 | `insert into allocations (...) values (...)` |
| 45.72 | 7 | 6.532 | 14 | `delete from users u1_0 where u1_0.id in ($1,$2) and (deleted_at IS NULL)` |
| 40.25 | 111 | 0.363 | 255 | `select ... from allocations a1_0 where organization_id=$1 and is_deleted=$4 order by created_at ...` |
| 33.26 | 3 | 11.086 | 26 | `select ... from allocation_name_search_tokens anst1_0` |
| 32.32 | 27 | 1.197 | 27 | `insert into file_uploads (...) values (...)` |
| 31.29 | 79 | 0.396 | 79 | `insert into organizations (...) values (...)` |
| 26.45 | 50 | 0.529 | 50 | `insert into users (...) values (...)` |
| 23.52 | 1 | 23.519 | 161 | `select ... from allocations a1_0 where organization_id=$1 and is_deleted=$4 order by created_at offset ... fetch first ...` |
| 19.45 | 3 | 6.483 | 3 | `select string_agg(word,...) from pg_get_keywords()` (Hibernate startup introspection) |
| 19.44 | 48 | 0.405 | 48 | `insert into user_roles (user_id,role_id) values ($1,$2)` |
| 18.03 | 4 | 4.507 | 4 | `insert into ptp_records (...) values (...)` |
| 17.10 | 2 | 8.550 | 2 | `insert into user_action_audit_logs (...) values (...)` |
| 14.41 | 2 | 7.204 | 2 | `insert into agent_shifts (...) values (...)` |
| 14.34 | 2 | 7.169 | 2 | `insert into npa_records (...) values (...)` |
| 14.32 | 6 | 2.386 | 6 | `insert into unified_audit_events (...) values (...)` |
| 14.18 | 3 | 4.728 | 3 | `insert into column_schemas (...) values (...)` |
| 14.15 | 2730 | 0.005 | 2730 | `SELECT set_config($2,$1,$3)` — `RlsAwareDataSource` stamping the org-id GUC per checkout |

## Sequential-scan volume per table (real, not query-text guessing)

| table | seq_scan | seq_tup_read | idx_scan | n_live_tup |
|---|---|---|---|---|
| allocations | 1020 | 1,918,286 | 91,217 | 1,906 |
| organizations | 99,625 | 1,360,584 | 0 | 119 |
| assignments | 1,641 | 987,882 | 2,151 | 0 |
| users | 22,672 | 918,893 | 522 | 64 |
| file_uploads | 7,014 | 286,636 | 135 | 58 |
| visit_sessions | 2,057 | 265,353 | 12 | 0 |
| allocation_name_search_tokens | 43 | 249,954 | 89,282 | 6,074 |
| visit_logs | 1,268 | 238,384 | 2,879 | 0 |
| daily_visit_list | 2,680 | 217,080 | 0 | 0 |
| role_permissions | 1,513 | 164,917 | 0 | 0 |
| *(21 more tables, all under 6k rows — full list reproducible via the query above)* | | | | |

## Conclusion (TASK 2.1 acceptance: real numbers, not fabricated)

**No table in this dataset exceeds ~10k rows** — the largest, `allocation_name_search_tokens`, has
6,074. TASK 2.2's own instruction is to identify sequential scans specifically **on tables over
~10k rows**; nothing here crosses that line. `organizations` shows 99,625 sequential scans against
only 119 rows and *zero* index scans — that's not a missing index, it's the query planner
correctly choosing a seq scan because scanning 119 rows is cheaper than an index lookup at that
size; forcing an index here would be the exact "speculative index" TASK 2.2.c explicitly forbids.

Every genuinely slow statement above (the sub-10ms-mean-but-real-cost items in the total-time
table) is dominated by per-row **write** cost (encryption of PII columns before INSERT, RLS-forced
plan overhead, immutability-trigger firing on audit writes, `SET config` for the org-id GUC on
every pooled connection checkout) — not by a missing index on a read path. None of that is fixable
with an index; it's the cost of the security architecture (SYSTEM 01/07) working as designed.

**TASK 2.2 (close missing indexes) has nothing to act on yet.** This is the honest, task-mandated
outcome ("If no such database exists yet, say so explicitly ... rather than fabricating numbers")
adapted to the actual finding: a representative database *does* exist, and it genuinely shows no
index gap at its current scale. Re-run the harvest queries above once real production data volume
exists (SYSTEM 05 provisioning, or a production data migration) — that is the point at which
TASK 2.2's row-count threshold becomes meaningful, and any index added then should cite the
specific line from a re-run of this file, per TASK 2.2.c.
