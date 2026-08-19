# SYSTEM 26 — Search/Filter/Sort Infrastructure: execution record

Executed from `docs/PRODUCTION-TASKLIST.txt`, SYSTEM 26 block, 2026-08-18 session.
Continuation of the same session that completed SYSTEM 35 and SYSTEM 18 TASK 18.1 + SYSTEM 28
TASK 28.1.

## Prerequisite check

SYSTEM 07 (encryption) — already deeply verified during the SYSTEM 35 session (`EncryptedStringConverter`/`LookupHashService` architecture is real). No new investigation needed.

## Major drift found before writing any code

The tasklist's CURRENT STATE says the borrower-name filter bug is unfixed ("the codebase already
has the correct mechanism... it is simply not used here") and predicts Allocation, Collection,
and Visit all have the same LIKE-on-ciphertext problem. Investigation found this only partially
true:

1. **`Allocation`'s borrower-name search was already fully built and working**, referencing a
   design spec (`docs/superpowers/specs/2026-08-06-global-search-design.md`) not mentioned in the
   tasklist at all: `AllocationNameSearchToken` entity/repository, `AllocationSearchIndexService`
   (reindexes on every write path — `AllocationImportProcessor`, `UploadDataServiceImpl`), a
   correct token-hash-based repository query, an `AllocationNameTokenBackfillRunner`, and a merged
   frontend (`GlobalSearchModal` deleted, folded into `CommandPalette`). The **only** actual bug
   was in the test itself (`AllocationRepositorySearchTest`): the loan-number-prefix assertion
   passed a raw substring to a `LIKE` pattern parameter without the trailing `%` the production
   caller (`AllocationServiceImpl`) always appends — so `LIKE` degraded to an exact-match
   comparison that could never pass. This is also the test that showed up as an unrelated,
   pre-existing failure in every earlier session today (SYSTEM 35, SYSTEM 18/28) — it was this
   bug all along, not something separate.
2. **A comprehensive grep (`cb.like`/`criteriaBuilder.like` across the whole server tree) found
   exactly one remaining LIKE-on-ciphertext site**: `PtpSpecification.withFilters()`'s
   `borrowerName` predicate. `Collection` has no encrypted field at all (the tasklist's prediction
   doesn't hold); `VisitLog`, `ChatSession`, `NpaRecord`, `PtpHistory`, `User`, `UserCreationRequest`
   all have encrypted fields but no LIKE/Specification-based search on them anywhere in the
   codebase.
3. **Severity nuance**: no current web or mobile UI actually sends `PtpFilterRequest.borrowerName`
   to the backend — both `PtpsPage.tsx` and the mobile equivalent search client-side over the
   already-fetched, already-decrypted page instead (an explicit, documented design choice: "there
   is no generic free-text searchTerm param... org scope is always derived from the JWT"). The bug
   was still real and required fixing (any direct API caller, including a future frontend change,
   would silently get zero results), just not actively user-visible on any *current* screen the
   way the tasklist's "single most user-visible defect" framing implies.

## TASK 26.1 — Fix encrypted-field search [DONE for the two real cases]

- **Allocation**: fixed the one-line test bug (`AllocationRepositorySearchTest`); verified the
  whole existing pipeline end to end (search-by-name-token, search-by-loan-prefix, no-filter).
- **PtpRecord**: applied the identical architecture already proven for Allocation —
  `ptp_name_search_tokens` table (migration `V094`, RLS policy denormalizing `organization_id`
  since `ptp_records` has none of its own — mirrors `rls_ptp_records_isolation`'s join-to-`allocations`
  approach), `PtpNameSearchToken` entity/repository, `PtpSearchIndexService`/Impl (reindexes on
  both PtpRecord write paths: `PtpServiceImpl.createPtp()` and `PtpImportProcessor.persistBatch()`
  — confirmed via full trace that `updatePtpStatus()` and the Lucien `CreatePtpTool` never touch
  `borrowerName` independently, so no other write path exists), `PtpNameTokenBackfillRunner`
  (flag-gated `app.backfill.ptp-name-tokens=true`, mirrors the Allocation one exactly),
  `PtpSpecification.withFilters()` rewritten to use a Criteria API `Subquery` against the token
  table instead of `cb.like` on ciphertext (the hash is computed once in `PtpServiceImpl.getAllPtps()`
  and passed in, since a static `Specification` factory has no Spring-managed dependencies of its
  own — mirrors how `AllocationServiceImpl` computes its hash before calling the repository).
- **26.1.f (UX honesty)**: no UI currently sends `borrowerName` for PTPs, so there was no filter
  copy to correct; documented the prefix-match (not "contains") semantics directly on
  `PtpFilterRequest.borrowerName`'s field javadoc instead.
- **26.1.g (apply to every encrypted field)**: verified via the comprehensive grep above — done;
  no other entity has this bug.
- **26.1.h (tests)**: `PtpBorrowerNameSearchTest` (new, real create→search integration test,
  confirms both token-row creation and correct search results) plus the fixed
  `AllocationRepositorySearchTest`.

## TASK 26.2 — Specification consistency audit [DONE] (2026-08-19 session)

### Drift from the task's own framing

26.2.a's literal instruction is "list every `*Specification` class" -- there are only two
(`AuditEventSpecification`, `PtpSpecification`), and both were already clean: every predicate is
conditionally added only when its filter value is non-null (and non-blank for the one String
field, `loanNumber`), so null/empty filters are correctly ignored rather than matching nothing.
No bug found there.

**The real, live vulnerability 26.2.b describes ("a caller sorting by an encrypted or unrelated
column can infer data") was NOT in either `*Specification` class -- it was in ad-hoc `Sort`
construction scattered across ~20 controller/service call sites that never went through a
`*Specification` at all.** Two failure modes, both real:

1. **`AllocationServiceImpl.buildSort()`** took `sortBy` straight from the request into
   `Sort.by(direction, field)` with zero validation. A caller could request
   `sortBy=borrowerName` -- an `EncryptedStringConverter` field -- sorting on ciphertext bytes, or
   any nonexistent property, which `findAllWithFilters`'s JPQL `ORDER BY` would fail to resolve at
   runtime (an uncaught 500, not the 400 ACCEPTANCE requires). This is the Allocations list --
   probably the single highest-traffic endpoint in the app.
2. **`AttendanceController.getByOrgAndDate()` and `VisitLogController`'s two paged endpoints**
   accepted a raw Spring-bound `Pageable` whose `Sort` is populated directly from the client's own
   `?sort=` query parameter by `PageableHandlerMethodArgumentResolver` -- which applies no
   allowlisting of its own -- then handed it straight to a JPA repository method
   (`findByOrgIdAndAttendanceDate`, `findByAgentIdAndIsDeletedFalse`,
   `findByOrganizationIdAndIsDeletedFalse`). `VisitLog.contactPerson`/`contactNumber` are also
   `EncryptedStringConverter` fields, same risk as Allocation's `borrowerName`.

A separate, already-existing `SafeSort` utility (`common/SafeSort.java`) was already correctly used
by 4 controllers (`UserController`, `PtpController`, `CollectionController`, one
`AssignmentController` endpoint) -- but its own `from()` silently substituted the default field for
an unrecognized request instead of rejecting it, which doesn't meet 26.2.b's own wording
("allowlist... and reject anything else") or the task's literal ACCEPTANCE.

### Fix

- `SafeSort.from()`: unrecognized field now throws `BusinessException` (400) instead of silently
  substituting. Fixes the behavior for all 4 pre-existing callers too, not just new ones.
- `SafeSort.sanitize(Pageable, allowed, defaultField, defaultDirection)` (new): the counterpart for
  a raw Spring-bound `Pageable` -- unsorted input keeps the endpoint's own default, a requested
  property is validated through the same `from()` allowlist/reject path.
- `AllocationServiceImpl.buildSort()` now calls `SafeSort.from()` with an explicit allowlist
  (createdAt, updatedAt, status, outstandingAmount, totalDue, loanNumber, assignedAt -- no
  encrypted fields).
- `AttendanceController`/`VisitLogController`'s three endpoints now sanitize their `Pageable` via
  `SafeSort.sanitize()` before it reaches the repository. Preserved each endpoint's original
  default direction exactly (`AttendanceController` previously had NO default sort at all --
  genuinely unsorted -- now defaults to `attendanceDate` DESC, a deliberate improvement rather than
  a behavior hazard, since "no deterministic order" is itself the class of bug TASK 26.3 exists to
  close).

**Indexing** (26.2.a's third sub-item): both `AuditEvent` and `PtpRecord` have composite indexes
covering every equality/range filter their Specifications use except `severity`/`result`
(AuditEvent) and the `loanNumber` LIKE/`reminderSent` filters (PtpRecord) -- all four are either
low-cardinality booleans/enums or a `LIKE '%...%'` pattern a btree index can't accelerate anyway
(would need a trigram/GIN index, a bigger infra decision). Every AuditEvent query is additionally
bounded by `organization_id` via RLS regardless. Flagged, not built -- performance-only, not a
correctness or security gap, and building a speculative index/extension with no real query-volume
data to justify it is the exact anti-pattern SYSTEM 02 TASK 2.2 already rejected (see
`docs/DB-HOTSPOTS.md`'s own conclusion).

## TASK 26.3 — Pagination correctness [26.3.a DONE, 26.3.b DEFERRED] (2026-08-19 session)

### 26.3.a — deterministic tiebreaker [DONE]

`SafeSort.withIdTiebreaker(Sort)` (new): appends `id` ascending as a final sort key unless the sort
already includes it. Applied to **every** paginated query in the codebase that builds a `Sort` --
not just the 26.2 vulnerability sites: all 8 `SafeSort`-consuming controllers get it automatically
(`from()` and `sanitize()` both route through it), plus ~14 more files that build a `Sort` from a
hardcoded literal field (`AgentFieldController`, `AuditEventController`, `AssignmentController`'s
second endpoint, `CalendarController`, `BorrowerController`, `AuditLogController` (5 sites),
`MessageTemplateController`, `LucienController`, `FraudCaseController`, `FileUploadController` (2
sites), `ReconciliationController` (2 sites), `UserCreationRequestController` (2 sites),
`ReportingController` (3 sites), `NpaController`) -- every one of those sorts by a field many rows
can tie on (`createdAt`, `status`, an amount, ...), so without a unique final key, rows can appear
on two pages or on none as data changes underneath a paging client. Confirmed every entity involved
has an `id` property (`String` for `ChatSession`, `UUID` for everything else -- `Sort.by("id")`
doesn't care about the underlying type).

### 26.3.b — keyset pagination [DEFERRED, not a code change]

**Not built.** `docs/DB-HOTSPOTS.md` (SYSTEM 02, real `pg_stat_statements` harvest against the only
representative database that exists) already found: no table in this codebase exceeds ~10k rows,
`allocations` itself has 1,906. Deep-OFFSET degradation is a phenomenon that bites at a materially
larger scale than anything measurable today -- no live OCI/production instance exists yet
(`docs/INFRA-CURRENT.md`, SYSTEM 05 not provisioned). Building keyset-pagination infrastructure now,
with zero real query-volume data to validate it against, is the identical speculative-optimization
mistake SYSTEM 02 TASK 2.2 explicitly declined to make for indexes. 26.3.b's own task wording says
"consider," not a hard ACCEPTANCE requirement (unlike 26.3.a, which the literal ACCEPTANCE line
covers). Revisit once real production data volume exists -- re-run `docs/DB-HOTSPOTS.md`'s harvest
queries against it first, per that file's own closing instruction.

## Verification

Full `mvn -f server/pom.xml clean test`: **594 tests, 0 failures, 0 errors, BUILD SUCCESS** — the
first fully-green full-suite run this session (every earlier run had exactly one failure:
`AllocationRepositorySearchTest`, now understood to be this system's own bug rather than an
unrelated pre-existing one). SYSTEM 26's own specified command
(`-Dtest='*Specification*Test,*Search*Test,*Filter*Test'`) run separately — see session's final
report for its pass/fail count.

TASK 26.2/26.3 (2026-08-19 session) — new tests: `SafeSortTest` (from()/sanitize()/
withIdTiebreaker() -- reject-unknown-field, blank/null defaults, tiebreaker presence and
non-duplication), `AllocationServiceImplSortTest` (disallowed/unknown field rejects before any
query; allowed field proceeds). Full `mvn -f server/pom.xml clean test` run at the end of this
session — see session's final report for pass/fail counts.
