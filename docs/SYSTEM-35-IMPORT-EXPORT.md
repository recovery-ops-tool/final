# SYSTEM 35 — Import/Export: execution record

Executed from `docs/PRODUCTION-TASKLIST.txt`, SYSTEM 35 block, 2026-08-17 session.

## Prerequisite check

- **SYSTEM 07 (encryption): confirmed real.** `EncryptedStringConverter` exists and is applied
  to `Allocation.borrowerName` and `Borrower.ckycId/phone/email/...`. Verified by direct read.
- **SYSTEM 25 (storage): NOT done.** `FileStorageService`/`ExportServiceImpl` write to local disk
  (`./reports/<orgId>/...`); there is no presigned/time-limited URL mechanism anywhere in the
  codebase. TASK 35.4 explicitly requires "deliver via time-limited link (SYSTEM 25)" — that
  infrastructure doesn't exist. **Decision (confirmed with the user): do TASK 35.1–35.3 in full,
  skip TASK 35.4 entirely rather than build ad hoc storage infrastructure that belongs to a
  different system.** TASK 35.4 is unstarted.

## Drift found vs. the tasklist file

1. **TASK 35.1.c said "the ColumnSchema already knows the mapping."** False —
   `ColumnSchema` (the DB entity) has no field-mapping attribute; it's only used for per-org
   required-column validation in `FileProcessingServiceImpl.validateRowDynamic`. The actual
   existing single source of truth for "which raw column is a dedicated field" is
   `EntityImportProcessor.fieldSpecs()` (`ImportFieldSpec` records — already used for the
   downloadable template and the upfront header check). **Decision (confirmed with the user):
   use `fieldSpecs()` + a new `ImportValues.stripDedicatedFields()` helper instead.**
2. **CURRENT STATE said phone/email/ckyc_id are encrypted "in their own dedicated columns on the
   SAME entity"** as borrower_name. Not quite — `borrowerName` is on `Allocation`; ckyc/phone/
   email are on the linked `Borrower` entity. Both are correctly encrypted; the fix needed to
   account for two entities, not one.
3. **Not drift, but the file's "verify, don't assume" instruction (35.1.d) paid off**: `Collection`,
   `PtpRecord`, and `VisitLog` have no JSONB dynamic-data column at all. Only `Allocation` does.
   The bug — and the fix — is Allocation-only.

## A second writer the tasklist didn't mention

`UploadDataServiceImpl.addRow()`/`.updateRow()` — the manual "edit a row after upload" UI —
independently dumped its raw `Map<String, Object> data` straight into `Allocation.dynamicData`,
the exact same bug via a second code path the tasklist audit never looked at. Fixing only
`AllocationImportProcessor` would have left this open. Fixed the same way, reusing the same
`fieldSpecs()` authority (see TASK 35.1 code changes below) rather than a second hardcoded list.

Manual entry also never linked a `Borrower` row at all (no `resolveOrCreateBorrowerId`
equivalent existed there), so naively stripping ckyc/phone/email from `dynamicData` would have
made a manually-typed phone number simply vanish — a data-loss regression, not a fix. Resolved by
promoting `AllocationImportProcessor`'s borrower resolve-or-create logic to
`BorrowerService.resolveOrCreateBorrower(...)`, now shared by both writers.

## TASK 35.1 — Stop duplicating PII into plaintext JSONB [DONE]

- `ImportValues.stripDedicatedFields(Map, List<ImportFieldSpec>)` — the shared stripping
  authority, alias-normalized (case/space/underscore/hyphen-insensitive), used by both writers.
- `AllocationImportProcessor.buildFromRow/updateFromRow` now store only `extraData(row)` in
  `dynamicData`, and resolve the Borrower link via `BorrowerService` instead of an inline copy of
  the same logic.
- `UploadDataServiceImpl.addRow/updateRow` — same fix, same authority, plus borrower resolution
  so ckyc/phone/email typed here land in their encrypted home instead of being dropped.
- **Backfill**: `db/migration/V093__backfill_allocation_dynamic_data_pii.sql`. Runs as a Flyway
  migration (not an app-level scheduled job) specifically so it runs with no
  `app.current_org_id` GUC set — `rls_allocations_isolation`'s `OR current_org_id() IS NULL`
  branch (see `V010__rls_policies.sql`) then grants it every organization's rows with no
  platform-admin bypass ceremony needed. Strips the same alias set as the Java fix, hand-kept in
  sync since this is a one-time backfill that never runs again (V093 already ran as part of this
  session — see `AllocationDynamicDataBackfillMigrationTest`, which replays the actual migration
  file's SQL against a simulated pre-fix row to verify it, since Flyway itself won't re-run it).
- **Residual risk, assessed per 35.1.f — NOT encrypting the whole `dynamic_data` JSONB column
  this session.** `dynamicData` is genuinely read across ~17 web pages plus `ContextAssembler`
  (Lucien's AI context) as an org's free-form "extra columns" feature; encrypting the whole
  column would require a schema/type change and touch every one of those read sites for one
  session's worth of risk that isn't justified by what's actually left exposed. What's left: if
  an org maps an *unrecognized* custom column that happens to contain incidental PII (e.g., a
  free-text "alternate contact" field), that value stays in plaintext `dynamic_data` — the
  recognized identity fields (name/ckyc/phone/email) are fully covered regardless of what an org
  names them, since matching is alias-based, not literal. Flagged as a follow-up, not solved here.

## TASK 35.2 — Import validation and error reporting [DONE]

Most of this already existed: whole-file header validation before any row is touched
(`assertRequiredHeadersPresent`), batched processing with a genuine partial-success contract
(`FILE_PROCESSING_PARTIALLY_FAILED`), and per-row error capture (row/column/message/raw value) via
`FileProcessingError` + a paginated `GET /{id}/errors` endpoint. The one real gap: no *downloadable*
report, only paginated JSON. Added `GET /api/v1/file-uploads/{id}/errors/download` (CSV,
`Content-Disposition: attachment`, same convention as the existing `/template` endpoint), covering
all errors for the upload, not just one page.

## TASK 35.3 — Memory-safe processing of large files [DONE]

Confirmed the concern: `FileProcessingServiceImpl` materialized the entire parsed file as
`List<Map<String, String>> allRows` and held it for the full processing duration (this is what
`allRows.size()` at the old line 133 was reading from). Fixed by adding a streaming parse path
(`FileParsingService.streamFile` + `RowHandler`) and restructuring `processFileAsync` into two
passes over the same in-memory byte array (`ByteArrayMultipartFile`, already re-readable — see
its `getInputStream()`):

1. **Scan pass** — streams the file once, counting rows and collecting only the loan-number/
   agent-email values needed for bulk prefetch, discarding each row immediately after.
2. **Processing pass** — streams the file again, handling one row at a time; `batch`/
   `batchErrors` are the only unbounded-by-filesize state, capped at `application.file.batch-size`
   (default 500) regardless of total row count.

`processRowsInBatches(..., List<Map<String,String>>)` keeps its exact existing signature and
behavior (an existing integration test, `FileProcessingBorrowerLinkTest`, calls it directly with a
hand-built list) — both paths now share the same per-row handling and batch-flush logic via
extracted private helpers, so there's one implementation, not two that could drift apart.

**Known, stated limitation**: this fixes the row-materialization blowup, which is what the task's
own evidence (`allRows.size()`) pointed at. It does not make Excel parsing itself streaming —
Apache POI's `usermodel`/`WorkbookFactory` API (used for `.xlsx`) buffers the whole workbook in
memory internally regardless of how the caller consumes rows afterward; true XLSX streaming
requires POI's SAX-based `eventusermodel`/`XSSFReader` API, a materially larger and riskier
undertaking (shared-strings table, style/date-format resolution, XML SAX handling) not
attempted here given the correctness risk to financial data. CSV parsing (`OpenCSV`) is now
genuinely line-by-line. Also out of scope: `FileStorageService.retrieve()` still returns the raw
file as a single `byte[]` rather than a stream — that's a SYSTEM 25 (storage) concern, and is a
fixed-size, much smaller buffer than the row-object blowup this task was about.

## TASK 35.4 — Export controls [PARTIAL — 3 of 4 sub-items done, 2026-08-19 session]

Revisits this doc's original "skip TASK 35.4 entirely" call. On closer reading, only ONE of
35.4.b's four requirements ("deliver via time-limited link (SYSTEM 25)") actually depends on
SYSTEM 25 -- the other three don't, and were real, separately-fixable gaps on the one concrete
export surface this codebase has: `ExportController.downloadReport()` (report-job file downloads,
which do move bulk PII out of the system per 35.4.a).

- **Explicit permission**: already satisfied, unchanged -- `@PreAuthorize(Authz.LEADS)`.
- **Audit every export with DATA_EXPORTED**: found the *download* step (as opposed to report
  *generation*, which is audited) already recorded an audit event (`ExportServiceImpl.exportReport()`)
  -- but as `REPORT_EXPORTED` (INFO severity), a codebase-specific action never named by this task,
  with no filter information. Switched to `DATA_EXPORTED` (HIGH severity -- correctly reflects
  that this is a bulk PII export, not routine INFO-level activity) and added `metadata.filters` =
  `ReportJob.parameters`, the original `ReportRequest` already serialized onto the row at
  generation time (`ReportingServiceImpl.enqueueReport`) -- no new capture needed, it already
  existed on the row.
- **Rate-limit exports per user**: report *generation* was already rate-limited
  (`ReportingController`); the *download* endpoint had none. Added the identical pattern
  (`RateLimiter` + new `AppProperties.Security.reportDownloadMaxAttempts/WindowMinutes`,
  30/5min default).
- **"row count"** (part of 35.4's own ACCEPTANCE line): **not captured, still a real gap.** No
  report builder in this codebase currently records a result-row-count anywhere on the `ReportJob`
  row -- adding one needs a schema change (`ReportJob.rowCount` or similar) threaded through every
  `ReportType`'s builder in `ReportJobExecutor`, a materially larger change than this pass's other
  fixes. Flagged, not attempted.
- **"deliver via time-limited link"**: still genuinely blocked on SYSTEM 25 (P1, not built) exactly
  as this doc's original session concluded -- object storage with presigned URLs doesn't exist;
  the file is streamed directly through the app with no expiry concept. Unchanged.

**Tests**: `ExportControllerTest` (new -- rate-limit rejection short-circuits before the org-isolation
check or the actual export call; within-limit proceeds normally), `ExportServiceImplTest` (new --
audits `DATA_EXPORTED` with `filters` from `job.getParameters()`; a null-parameters job omits the
key rather than throwing).

## Verification

SYSTEM 35's own specified command (`-Dtest='*Import*Test,*Export*Test,*FileProcessing*Test'`) —
22/22 passed. Full `mvn -f server/pom.xml clean test` run at the end of this session — see
session's final report for pass/fail counts.
