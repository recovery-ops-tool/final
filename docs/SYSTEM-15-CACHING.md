# SYSTEM 15 — Caching: execution record

Executed from `docs/PRODUCTION-TASKLIST.txt`, SYSTEM 15 block, 2026-08-18 session.
Continuation of the same session that completed SYSTEM 35, SYSTEM 18 TASK 18.1, SYSTEM 28
TASK 28.1, and SYSTEM 26 TASK 26.1.

## Prerequisite check

SYSTEM 01 (RLS/tenancy) — architecture confirmed real and working repeatedly across this session
(RLS policies read/verified for allocations, ptp_records, allocation_name_search_tokens, and the
new ptp_name_search_tokens table). No new investigation needed; the formal automated coverage
guard (SYSTEM 01 TASK 1.1) hasn't been built, but that's a different, narrower gap than what
SYSTEM 15 depends on.

## TASK 15.1 — Wire user cache eviction to every mutation [DONE]

Confirmed the tasklist's claim exactly: `CustomUserDetailsService.evictUserCache()` existed,
correctly annotated `@CacheEvict`, and was called from nowhere. `JwtAuthenticationFilter` calls
the cached `loadUserByUsername()` on every authenticated request, confirmed by reading it directly
— so this cache genuinely is the live authorization gate, not just a login-time optimization.

Wired `evictUserCacheAfterCommit(email)` (a new private helper using
`TransactionSynchronizationManager.registerSynchronization(...).afterCommit()`, falling back to an
immediate call when no transaction is active) into all 8 identity/role/permission/status-changing
methods in `UserServiceImpl` (one more than the tasklist's "seven at audit time" — `updateUser`
needed it too, since it can change the cache-key-bearing email itself):
`updateUser` (evicts both old and new email), `assignRole`, `removeRole`, `enableUser`,
`disableUser`, `deleteUser` (evicts the pre-anonymization email), `grantDirectPermission`,
`revokeDirectPermission`.

**Cross-instance staleness (15.1.d)**: did not add Redis pub/sub broadcast. `TwoTierCache.evict()`
already clears both L1 (this instance's Caffeine) and L2 (shared Redis) — confirmed by reading it
— but a *different* instance's own L1 entry is untouched by any single instance's evict call.
`RedisCacheConfig` already sets `userDetails`' L1 Caffeine TTL to **30 seconds** (separately from
its 5-minute L2/Redis TTL) — this was already the case before this session, not something added
now. That bounds every other instance's worst-case staleness window to 30 seconds regardless, which
is the explicit second option TASK 15.1.d itself offers ("or set L1 TTL short enough... and
document the choice") — documented in code comments on the new helper rather than building new
pub/sub infrastructure for marginal benefit over an already-short window.

**A genuine risk surfaced and resolved during testing, not a real production bug**: a lightweight
Spring caching test (bare `AnnotationConfigApplicationContext`, no Spring Boot auto-configuration)
initially failed to wire `CustomUserDetailsService` into `UserServiceImpl`'s constructor with a
`BeanNotOfRequiredTypeException` — because plain Spring's `@EnableCaching` default
(`proxyTargetClass=false`) JDK-proxies `CustomUserDetailsService` to only its `UserDetailsService`
interface, which doesn't satisfy a constructor parameter typed to the concrete class. Verified
directly against the *real* Spring Boot app context (`PtpAndRestructureIsolationTest`, a full
`@SpringBootTest`) that this is **not** an actual production issue: Spring Boot's own
`AopAutoConfiguration` defaults `proxy-target-class` to `true` (CGLIB) unless overridden, and this
app doesn't override it — confirmed by running that real-context test successfully both before and
after the `UserServiceImpl` change. The lightweight test config was fixed to declare
`@EnableCaching(proxyTargetClass = true)` explicitly, matching Spring Boot's actual default rather
than plain Spring's, so it now faithfully represents what really runs.

**Tests**: `UserServiceImplCacheEvictionTest` (new) — exercises the real Spring caching proxy
(same lightweight pattern as the pre-existing `AgentContextServiceImplCachingTest`): disabling a
user immediately flips `UserDetails.isEnabled()` on the very next lookup; assigning a role
immediately adds the new authority on the very next lookup — the literal acceptance criterion
("deactivating a user immediately blocks their next request") proven end to end, not just that
`evictUserCache()` was called. `UserServiceImplTest` (existing, pure-Mockito) still covers the
wiring itself and continues to pass unmodified in behavior (only its constructor call gained the
new mock).

## TASK 15.2 — Verify tenant-safe cache keys [DONE — audited, no bug found]

Full audit (not a sample — there are only 3 `@Cacheable`/`@CacheEvict`/`@CachePut` namespaces in
the entire codebase, all read directly):
- `userDetails`, keyed by `#email` — safe: `User.email` has `@Column(unique = true)`, globally
  unique across the platform, not per-org, so the key alone already discriminates every tenant.
- `lucienContext`, keyed by `#sessionId` — safe: session IDs are globally unique per session,
  same reasoning.
- `systemPrompts`, keyed by `#promptKey` — safe: `SystemPromptConfig` (`lucien_system_prompts`
  table) has `promptKey` as a globally `unique` column and **no `organization_id` column at
  all** — it's a genuinely platform-wide table by design, not org-scoped data with a missing key
  component.

No code changes were needed for this task — there is no cross-tenant cache leak in the current,
very small cache surface. Locked in with `loadUserByUsername_twoDifferentOrgsUsers_neverCrossContaminate`
in the same new test file, proving two different orgs' cached users never collide.

## TASK 15.3 — Cache metrics and stampede protection [DONE] (2026-08-19 session)

### 15.3.a — Micrometer export

`recordStats()` was already on for every L1 Caffeine cache (this file, prior session) but nothing
ever exported those stats. Root cause: Spring Boot's own cache-metrics auto-binder
(`CacheMetricsRegistrar`) only recognizes a cache that IS a `CaffeineCache`, and
`TwoTierCacheManager` hands every caller a `TwoTierCache` wrapper instead — so the auto-binder
silently bound nothing, with no error or warning.

Fixed in `RedisCacheConfig.cacheManager()`: each L1 Caffeine cache's *native* `Cache` (unwrapped
via `getNativeCache()`, before the `TwoTierCache` wrapper is built) is registered directly with
`CaffeineCacheMetrics.monitor(meterRegistry, native, cacheName)`. This sidesteps the wrapper-type
mismatch entirely and puts `cache_gets_total`/`cache_puts_total`/`cache_evictions_total`/
`cache_size` on `/actuator/prometheus`, tagged by `cache=userDetails|lucienContext|systemPrompts`.

**Found while wiring this — two dead cache buckets, deleted**: `RedisCacheConfig` also declared a
`"featureFlags"` and a `"default"` L1 Caffeine cache + matching Redis `withCacheConfiguration`
entries. Grepped every `@Cacheable`/`@CacheEvict`/`@CachePut` and every `cacheManager.getCache(...)`
call in `server/src/main/java` — neither name is referenced anywhere. Feature-flag resolution is
cached by `FeatureFlagService` directly against Redis (a deliberate SYSTEM 20 TASK 20.3 design, a
prior session, not this cache manager), and nothing anywhere uses a cache literally named
`"default"`. Registering metrics for these would have shipped two permanently-empty series that
look exactly like real caches nothing uses — actively misleading on a dashboard — so they were
removed instead of instrumented.

**Test**: `RedisCacheConfigTest` (new) — calls the `cacheManager()` bean method directly (no Spring
context needed) and asserts `cache.gets`/`cache.puts` meters exist for all 3 real caches, and that
`"featureFlags"`/`"default"` are gone from `getCacheNames()`.

### 15.3.b — Stampede protection

Two different mechanisms were needed, because the codebase's caching isn't uniform (SYSTEM 15
TASK 15.2 already established there are only 3 `@Cacheable` namespaces, plus feature-flag
resolution's own hand-rolled Redis cache — see that task's audit above):

**userDetails / lucienContext / systemPrompts** (`TwoTierCache`-backed, Spring `@Cacheable`): added
`sync = true` to all three annotations, and rewrote `TwoTierCache.get(Object key, Callable<T>
valueLoader)` — the method Spring's cache aspect calls only when `sync = true` — to route through
the *native* Caffeine cache's atomic `get(key, mappingFunction)` instead of the previous
`l1.get()` / `l2Read()` / `valueLoader.call()` / `put()` sequence. That sequence was not atomic:
`sync = true` alone would NOT have fixed the stampede, since concurrent callers could each pass the
L1-miss and L2-miss checks before any of them reached `valueLoader.call()`. Caffeine's native
`Cache.get(key, Function)` guarantees the function runs at most once per key, with other callers
blocking on the in-flight computation — that guarantee now backs all three caches' loader path
(L2/Redis check plus `valueLoader.call()` plus L2 write-through, all inside the one atomic mapping
function).

**Feature-flag resolution** (`FeatureFlagService.isEnabled()`): not behind Spring's cache
abstraction at all (hand-rolled `StringRedisTemplate` reads/writes, SYSTEM 20 TASK 20.3), so it had
no Caffeine layer to make atomic. Added a small in-process single-flight cache
(`stampedeGuard`, a `Caffeine` `Cache<String, Boolean>`, 5s `expireAfterWrite` as a safety bound
only) wrapping the existing Redis-then-DB resolution. Correctness against staleness (not just
stampede) required wiring `evictCache()` to also `stampedeGuard.invalidate(key)` on every write —
without that, a write on the very instance that just wrote it could still serve its own stale
locally-cached read until the 5s bound expired, which would have silently regressed the guarantee
TASK 20.3's own test locks in (`set_evictsCache_soNextEntitlementCheckReflectsChangeImmediately`,
unmodified, still passes). Keyed on `organizationId + flagKey` only, not `defaultIfMissing` — the
sole caller (`EntitlementServiceImpl.hasFeature()`) always passes `false`, documented on the field
since a future caller passing a different default per key would need this reworked. Registered with
`CaffeineCacheMetrics` too (`"featureFlagResolution"`), for the same 15.3.a reasons.

Constructor change: `FeatureFlagService` now takes a `MeterRegistry` (Lombok
`@RequiredArgsConstructor`); `initStampedeGuard()` is `@PostConstruct` (package-private, so the one
non-Spring test construction path — `FeatureFlagServiceTest` — calls it explicitly after `new`).

"Report data" (task text's other named example): confirmed via TASK 15.2's exhaustive audit there
is no such cache anywhere in the codebase to protect — not deferred, genuinely doesn't exist yet.

**Tests** (all new): `AgentContextServiceImplCachingTest.buildContext_concurrentColdSession_
collapsesToOneComputation` and `FeatureFlagServiceTest.isEnabled_concurrentColdKey_
collapsesToOneDbResolution` — both spin up N threads racing a `CountDownLatch`-gated call against
the same never-before-seen key, with a `Thread.sleep` in the mocked backing call to widen the race
window, and assert exactly one backing invocation plus identical results across all callers.
`RedisCacheConfigTest.caffeineCache_getOrCompute_collapsesConcurrentColdKeyToOneComputation` proves
the same guarantee at the raw-Caffeine-primitive level `TwoTierCache` now relies on. This is the
literal acceptance wording: "a concurrent cold-key test produces one backing computation, not many."

## Verification

Targeted: `mvn -f server/pom.xml test -Dtest='FeatureFlagServiceTest,AgentContextServiceImplCachingTest,
SystemPromptServiceImplTest,UserServiceImplTest,EntitlementServiceImplTest,RequiresFeatureAspectTest'`
— 49/49 passed. `-Dtest='*Cache*Test'` (SYSTEM 15's own specified command) — 6/6 passed
(`RedisCacheConfigTest` + `UserServiceImplCacheEvictionTest`; doesn't glob-match the other cache
tests above by class name, run separately). Full `mvn -f server/pom.xml clean test` run at the end
of this session — see session's final report for pass/fail counts.
