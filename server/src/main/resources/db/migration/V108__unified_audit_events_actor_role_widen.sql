-- SYSTEM 10 TASK 10.4 (found via a real integration test, not a mock): actor_role VARCHAR(100)
-- overflowed for any principal with a realistic permission set once AuditServiceImpl.joinRoles
-- (bug, fixed alongside this migration) joined every granted authority instead of just
-- ROLE_-prefixed ones -- FO alone produced 137 chars, ORG_ADMIN 329, PLATFORM_ADMIN 456, causing
-- the INSERT to fail (and, since nothing caught it, the caller's real request to fail with it) for
-- almost every authenticated action in the app. The application-side fix alone makes a single
-- role's value comfortably fit; this widens the column as defensive headroom for the case this
-- schema already supports -- a user holding multiple roles at once (User.roles is a Set) -- rather
-- than relying solely on today's role/permission-naming conventions never growing past 100 again.

ALTER TABLE unified_audit_events ALTER COLUMN actor_role TYPE VARCHAR(500);
