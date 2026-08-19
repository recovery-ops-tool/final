# SYSTEM 06 — CI/CD: execution record

Executed from `docs/PRODUCTION-TASKLIST.txt`, SYSTEM 06 block (LAYER 0, P1), 2026-08-18 session.

## Prerequisite check

None.

## TASK 6.1 — Confirm current state [DONE]

The task's own CURRENT STATE text ("not verified to exist... audit found no evidence of a
configured pipeline") was already stale before this session started — SYSTEM 05's TASK 5.1
investigation had already confirmed `.github/workflows/` has 3 real, working files:
`server-ci.yml` (a `build-and-test` job running `mvn -B test` against real Postgres/Redis service
containers, plus a separate `migration-validate` job added by SYSTEM 03 TASK 3.1), `web-ci.yml`
(install, lint, build), `mobile-ci.yml` (install, typecheck, lint). All three cache their
dependency managers already (`cache: maven` / `cache: npm`).

## TASK 6.2 — Core build pipeline [DONE, pre-existing]

Every literal sub-requirement is already satisfied by what's there, with one structural deviation
kept deliberately:

- **3 separate workflow files instead of one `ci.yml` with 3 jobs.** Kept as-is rather than
  consolidated — each file is path-filtered (`paths: ['server/**', ...]`), so a web-only PR
  doesn't burn CI minutes running the server test suite and vice versa. A single `ci.yml` would
  lose that for no real benefit; the task's "Job 1/Job 2/Job 3" structure is satisfied in spirit
  by `server-ci.yml`'s two jobs + `web-ci.yml`, just organized as separate files.
- Server: `mvn -B test` runs the full suite (not just compile). Left as `test` rather than
  literally renamed to `verify` — with no plugin bound to the Maven `verify` phase (see TASK 6.3
  below for why dependency-check wasn't added there), the two commands are behaviorally identical
  here; renaming would be pure churn.
- Web: `npm run build` is `node scripts/check-reset-scope.mjs && tsc -b && vite build` (checked
  `web/package.json` directly) — `tsc -b` already performs the typecheck TASK 6.2.c asks for, so
  no separate step was needed.
- Migrations: `migration-validate` job (SYSTEM 03 TASK 3.1) already does exactly what 6.2.d asks.
- Caching: already present everywhere.

**Not independently re-verified via a live GitHub Actions run this session** — verified via local
equivalents instead (`mvn -f server/pom.xml test`, `npm ci && npm run build` for web, `npx tsc
--noEmit && npm run lint` for mobile — all pass). Per the user's explicit direction this session,
the team is staying on one shared branch/repo until all 42 systems are done, then moving to a
clean repo for the real OCI deploy — so a live push-and-watch verification was deferred to when
this session's batch of changes is actually pushed, not done proactively mid-audit.

## TASK 6.3 — Security scanning in CI [DONE]

**Dependency vulnerability scanning** — chose GitHub Dependabot alerts over the OWASP
dependency-check Maven plugin (task's own wording explicitly allows either for Maven). Reason:
OWASP's plugin downloads the full NVD CVE database on every run and is well known to hit NVD's
public rate limits hard without a registered API key (not available in this environment) — a real
risk of slow or outright-failing CI runs, which would undercut TASK 6.2.e's whole point (fast CI
so people don't start skipping it).

- `.github/dependabot.yml` — version-update schedules for `server` (maven), `web` (npm), `mobile`
  (npm), and `github-actions` itself (weekly).
- `.github/workflows/dependency-review.yml` — new, repo-wide (not path-filtered — a new vulnerable
  dependency anywhere is worth blocking regardless of which folder it lands in), runs on every PR.
  Uses `advanced-security/maven-dependency-submission-action` first (Maven's dependency graph
  isn't auto-submitted to GitHub the way npm's `package-lock.json`-derived one is — without this
  step `dependency-review-action` would only ever see web/mobile's npm deps, silently missing
  `server/pom.xml` entirely), then `actions/dependency-review-action` with
  `fail-on-severity: high` — GitHub's severity scale tops out at `critical`, so `high` as the
  floor covers both levels TASK 6.3.c names ("HIGH/CRITICAL"), nothing above exists to miss.
- `npm audit --audit-level=high` added to both `web-ci.yml` and `mobile-ci.yml`, non-blocking
  (`continue-on-error: true`) with a dated comment, mirroring the existing precedent in this same
  repo for the 610 pre-existing lint errors. Checked current findings before wiring this in, not
  blind:
  - **Web**: 2 findings. Fixed `nanoid` via `npm audit fix` (safe, patch-level; confirmed `npm run
    build` still succeeds after). `xlsx` (SheetJS) has an unpatched prototype-pollution/ReDoS
    advisory with **no fix available** — checked its actual usage
    (`web/src/utils/visitExport.ts`): only ever calls `XLSX.write`/`aoa_to_sheet` on the app's own
    generated data, `XLSX.read` is never called anywhere in `web/src`. The exploitable path
    (parsing a malicious spreadsheet) isn't reachable — the real untrusted-upload path is
    server-side, using Apache POI (`server/pom.xml`, `poi-ooxml:5.2.5`), a completely unrelated
    library. Left non-blocking with this reasoning documented inline.
  - **Mobile**: 15 findings → 14 after the same `nanoid` fix (confirmed `npx tsc --noEmit` still
    clean afterward). The remaining 14 are almost entirely transitive noise within Expo's own
    Metro/`react-native-reanimated` toolchain — `npm audit fix --force` would downgrade across
    Expo's pinned SDK compatibility matrix, a real risk not worth taking blindly just to silence a
    CI check. Left non-blocking, flagged for a dedicated triage pass.
- **Repo settings enabled via `gh api`** (public repo — all of the below are free, no GitHub
  Advanced Security purchase needed): `vulnerability-alerts` (was disabled — 404 on GET before,
  204 after), `security_and_analysis.dependabot_security_updates`, `.secret_scanning`,
  `.secret_scanning_push_protection` (blocks a push containing a detected secret before it lands,
  not just after-the-fact detection). Two narrower sub-toggles
  (`secret_scanning_non_provider_patterns`, `secret_scanning_validity_checks`) did not take via the
  same API call and were left as-is — likely gated behind GitHub Advanced Security even on a
  public repo; not chased further given the core protections were already on.

**Secret scanning** — `.github/workflows/secret-scan.yml` (new), runs `gitleaks` on push to
main/master and on every PR, `fetch-depth: 0` (full history, not just the working tree — a secret
added and later removed in a subsequent commit is still exposed to anyone who clones the repo).

- Checked first, urgently, before building anything: is `server/.env` (which has real-looking AWS
  credentials) tracked in git anywhere, past or present? `git ls-files server/.env` → empty,
  `git log --all -- server/.env` → empty. It's `.gitignore`'d and has never been committed. No live
  exposure.
- **Verified detection end-to-end locally, without ever pushing anything to the real remote** (a
  public repo — pushing even a "fake" AWS-formatted key risks tripping GitHub's own
  secret-scanning partner program against a real provider). Downloaded the `gitleaks` CLI binary
  to a scratch directory, created a throwaway local git repo (never touched `origin`), committed a
  randomized (not the well-known `AKIAIOSFODNN7EXAMPLE` docs placeholder, which gitleaks
  allowlists by default — confirmed that one produces zero findings, then retested with a random
  key) fake AWS access key + secret. `gitleaks detect` caught both (`aws-access-token` and
  `generic-api-key` rules) and exited `1`. Deleted the throwaway repo and binary afterward.
- **Also ran gitleaks read-only against the real repo's full history** (181 commits) as due
  diligence — found 4 hits, all confirmed false positives: `AMBIENT_KEY = "LUCIEN_AMBIENT_VISIT_V1"`
  (a lookup-key constant name, not a secret, in `DefaultSystemPrompt.java` and a docs plan file)
  and a multi-line regex artifact in `server/.env.example` (`JWT_SECRET=` immediately followed by
  the non-secret `JWT_EXPIRATION_MS=3600000`, which the `generic-api-key` rule mismatches as a
  continuation of the previous line's secret). Added `.gitleaksignore` with the 4 specific
  `commit:file:rule:line` fingerprints (not a broad path exclusion, so a genuinely new secret in
  either file still gets caught) and the reasoning for each. Re-ran gitleaks afterward: `no leaks
  found`, confirming the suppression works and the workflow won't be permanently red on these.

## TASK 6.4 — Branch protection and deploy [PARTIAL — 1 of 3 sub-tasks done]

- **6.4.a (branch protection)** — explicitly deferred per the user: the team is working on one
  continuous branch (`feature/call-recording-and-hardening`) directly, not via PR merges, until
  all 42 systems are done and a clean repo is created for the actual OCI deploy. A "require green
  CI before merge" gate has no PR-based workflow to attach to yet and would only add friction to
  how the team is actually working right now. Revisit when the clean deploy repo exists.
- **6.4.b (deploy workflow)** — deferred. SYSTEM 05 confirmed the OCI deployment target but no
  live instance is provisioned yet (`docs/INFRA-CURRENT.md`) — a rolling-deploy-with-rollback
  workflow needs a real SSH target and secrets to reference; writing one now would be untested
  guesswork against infrastructure that doesn't exist. Build this once SYSTEM 05's actual
  provisioning happens (likely in the clean deploy repo the user described, not this one).
- **6.4.c (git SHA at `/actuator/info`) — DONE, verified.** Added
  `io.github.git-commit-id:git-commit-id-maven-plugin:9.0.1` (generates `git.properties` — commit
  SHA, branch, dirty flag, build time) and a `build-info` execution on the existing
  `spring-boot-maven-plugin` (generates `build-info.properties` — artifact/name/version/time) to
  `server/pom.xml`. Exposed the `info` actuator endpoint
  (`management.endpoints.web.exposure.include`) and added `/actuator/info` to
  `SecurityConfig.PUBLIC_PATHS` — deploy tooling and anyone tracing "what commit is actually live"
  needs this reachable without a JWT, the same reasoning already applied to `/actuator/health` and
  `/actuator/prometheus`; build/git metadata isn't secret.

## Verification

- `mvn -f server/pom.xml test`: full suite green after the `pom.xml` plugin additions (no
  regression from adding `build-info`/`git-commit-id-maven-plugin`).
- **Real boot test**: packaged jar, `SPRING_PROFILES_ACTIVE=prod`, confirmed
  `git.properties`/`build-info.properties` both present inside the jar
  (`unzip -p ... BOOT-INF/classes/git.properties`), then booted and curled
  `GET /actuator/info` → `200`, body includes
  `"git":{"branch":"feature/call-recording-and-hardening","commit":{"id":"4ca9f00", ...}}` — the
  actual current commit's abbreviated SHA, exactly what TASK 6.4.c's acceptance check asks for.
  Process stopped after confirming.
- `npm run build` (web) and `npx tsc --noEmit` (mobile) both still pass after the `npm audit fix`
  dependency bumps.
- All 6 new/changed workflow and config YAML files (`dependabot.yml`, `dependency-review.yml`,
  `secret-scan.yml`, `web-ci.yml`, `mobile-ci.yml`, `server-ci.yml`) parsed successfully with
  `js-yaml` — syntax-valid. **Not verified**: an actual live GitHub Actions run of any of the new
  or changed workflows (per the user's "same branch until all 42 systems are done" direction —
  this session's changes haven't been pushed). Re-verify with a real push before relying on these.
