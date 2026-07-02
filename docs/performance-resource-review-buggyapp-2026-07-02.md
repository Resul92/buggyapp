# Performance Resource Review - buggyapp - 2026-07-02

> **Note:** Confluence MCP integration was not available in this automation environment. This document follows the requested Confluence page structure and can be copied into Confluence manually.

---

## 1. Executive Summary

**buggyapp** is an open-source **chaos-engineering / JVM problem simulator** (Java 8, Apache Ant, Servlet 2.4 WAR). It intentionally implements CPU spikes, memory leaks, thread leaks, connection leaks, N+1 database access, GC pressure, and related failure modes for training and APM tooling.

This review scanned all 81 Java source files plus deployment configuration (`Dockerfile`, `web.xml`, `context.xml`, `log4j2.xml`, `build.xml`).

| Severity | Count | Notes |
|----------|-------|-------|
| Critical | 12 | Mostly **intentional** demo scenarios |
| High | 20 | Mix of intentional demos and operational guardrails |
| Medium | 14 | Config, lifecycle, and logging overhead |
| Low | 6 | Micro-optimizations in teaching examples |

**Key takeaway:** The majority of findings are **by design**. Remediation of demo scenarios would undermine the product purpose. This review documents all patterns and applies **only small, high-confidence guardrail fixes** where stop/lifecycle handlers were broken or infrastructure config added unnecessary runtime overhead.

**Applied fixes (PR linked in Section 8):**
- Corrected inverted start/stop handlers in `LaunchPad.stop()` for `bug2` and `bug9`
- Added cooperative stop flags to `SlowFinalizeDemo`, `ExceptionsDemo`, and `memoryleakthread.MapManager`
- Removed unsafe `Thread.stop()` call in `CPUSpikeDemo.stop()`
- Reduced Log4j2 internal diagnostic overhead and bounded async log queue
- Removed unused `HttpServletRequest` retention from servlet worker thread

---

## 2. Repository/Stack Detected

| Component | Details |
|-----------|---------|
| **Repository** | `Resul92/buggyapp` (origin: ycrash/buggyapp) |
| **Language** | Java 8 (source/target 1.8 in `build.xml`) |
| **Build** | Apache Ant (`ant dist`, `ant dist-ee`, `ant dist-cmd`) |
| **Runtime (Docker)** | AdoptOpenJDK 11, webapp-runner on port 9010 |
| **Web** | Servlet 2.4, `LaunchBuggyAppServlet` at `/launch-buggyapp` |
| **Logging** | Log4j2 2.24.3 (async appender + rolling file) |
| **JSON** | Gson 2.3.1 |
| **Databases** | MySQL connector 5.1.46 (CLI DB leak demo); H2 in-memory (chattiness) |
| **Networking** | LittleProxy 1.1.2 + Netty 4.0.44 (network lag proxy) |
| **Tests** | TestNG 6.8 + Spring Mock (servlet tests) |

### Entry Points

| Entry | Location | Role |
|-------|----------|------|
| CLI | `com.buggyapp.LaunchPad#main` | Primary launcher for 20+ scenarios (`bug1`…`bug21`, `PROBLEM_*`) |
| Programmatic | `LaunchPad.start()` / `LaunchPad.stop()` | Used by servlet and tests |
| HTTP | `GET/POST /launch-buggyapp` | Web UI trigger for subset of demos |
| Docker | `Dockerfile` ENTRYPOINT | `-jar webapp-runner.jar buggyapp.war` on port 9010 |

---

## 3. Critical Findings

### C-1: Unbounded HTTP connection leak with 200-thread pool × 10,000 tasks
- **File:** `src/com/buggyapp/httpconnectionleak/HttpConnectionLeakSimulator.java`
- **Class:** `HttpConnectionLeakSimulator.start1()`
- **Evidence:** `Executors.newFixedThreadPool(200)` submits 10,000 tasks; each runs 10,000 inner HTTP calls; `conn.disconnect()` commented out; pool never shut down.
- **Impact:** Connection pool exhaustion, thread exhaustion, extreme CPU from scheduling overhead.
- **Severity:** Critical | **Confidence:** High
- **Status:** Intentional demo (`bug19` / `HTTP_CONNECTION_LEAK`). Document only.

### C-2: Infinite DB connection leak without close
- **File:** `src/com/buggyapp/dbconnectionleak/DBConnectionLeak.java`
- **Class:** `leakConnections()` / `leakConnection()`
- **Evidence:** `while(true)` opens new `DriverManager.getConnection()`; `closeConnection()` commented out in `finally`.
- **Impact:** DB connection pool exhaustion, server-side connection limit hit.
- **Severity:** Critical | **Confidence:** High
- **Status:** Intentional demo (`bug11`). Document only.

### C-3: Full-table `SELECT *` on every leaked connection
- **File:** `src/com/buggyapp/dbconnectionleak/DBConnectionLeak.java` (line 54)
- **Evidence:** `"SELECT * FROM "+tableName` with no `LIMIT`; loads entire table per connection.
- **Impact:** RAM spike, DB I/O saturation, GC pressure.
- **Severity:** Critical | **Confidence:** High
- **Status:** Intentional demo. Document only.

### C-4: Infinite file handle leak
- **File:** `src/com/buggyapp/fileleak/FileLeakSimulator.java`
- **Evidence:** `while(true)` opens `FileInputStream`, stores in `ArrayList`, never closes.
- **Impact:** OS file descriptor exhaustion ("too many open files").
- **Severity:** Critical | **Confidence:** High
- **Status:** Intentional demo. Document only.

### C-5: 1M file channels retained without close
- **File:** `src/com/buggyapp/fileleak/FileChannelLeakSimulator.java`
- **Evidence:** Loop to 1,000,000; channels stored in list, never closed.
- **Impact:** FD exhaustion, native memory pressure.
- **Severity:** Critical | **Confidence:** High
- **Status:** Intentional demo (`bug18`). Document only.

### C-6: Unbounded thread creation
- **File:** `src/com/buggyapp/threadleak/ThreadLeakDemo.java`, `ForeverThread.java`
- **Evidence:** `while(flag)` spawns new thread every 100ms; each sleeps 10 minutes.
- **Impact:** Thread exhaustion, native memory growth, scheduler overhead.
- **Severity:** Critical | **Confidence:** High
- **Status:** Intentional demo (`bug4`). Document only.

### C-7: N+1 query storm × 100 threads × infinite loop
- **File:** `src/com/buggyapp/chattiness/ChattinessDemo.java`
- **Evidence:** ~1M IDs loaded; `getUsersByIds()` runs one query per ID; 100 concurrent threads in `while(true)`.
- **Impact:** DB connection storm, CPU from query parsing, network saturation.
- **Severity:** Critical | **Confidence:** High
- **Status:** Intentional demo (commented out; H2 variant active). Document only.

### C-8: 50 million sequential DB queries
- **File:** `src/com/buggyapp/chattiness/H2ChattinessDemo.java`
- **Evidence:** `for (int i = 1; i <= 50000000; i++)` individual prepared statements.
- **Impact:** Sustained CPU, connection hold time, GC from result objects.
- **Severity:** Critical | **Confidence:** High
- **Status:** Intentional demo (`bug16` / `CHATTINESS`). Document only.

### C-9: Unbounded HashMap memory leak (OOM)
- **File:** `src/com/buggyapp/memoryleak/MapManager.java`
- **Evidence:** `while(flag)` inserts large strings with no eviction.
- **Impact:** Heap exhaustion, long GC pauses, OOM.
- **Severity:** Critical | **Confidence:** High
- **Status:** Intentional demo (`bug1`). Has stop flag wired.

### C-10: Unbounded static HashMap (near-OOM without crash)
- **File:** `src/com/buggyapp/memoryleaknooom/MemoryLeakNoOOMDemo.java`
- **Evidence:** Static `HashMap` grows to ~90% heap, throttles with sleep, never evicts.
- **Impact:** Sustained high RAM, GC thrashing.
- **Severity:** Critical | **Confidence:** High
- **Status:** Intentional demo (`bug1.2`). Has stop flag wired.

### C-11: New thread per HTTP request (servlet)
- **File:** `src/com/buggyapp/servlet/LaunchBuggyAppServlet.java`
- **Class:** `doProcess()`, `BuggyAppThread`
- **Evidence:** Comment acknowledges issue; `new BuggyAppThread(...).start()` with no pool or limit.
- **Impact:** Thread exhaustion under web load; request object retention.
- **Severity:** Critical | **Confidence:** High
- **Status:** Partially mitigated — removed unused `HttpServletRequest` retention. Bounded executor remains a human review item.

### C-12: Infinite HashMap growth without stop flag (bug1.1 path)
- **File:** `src/com/buggyapp/memoryleakthread/MapManager.java`
- **Evidence:** `while(true)` with no stop flag; not wired to `LaunchPad.stop()`.
- **Impact:** Unstoppable memory growth when launched via web/CLI stop path.
- **Severity:** Critical | **Confidence:** High
- **Status:** **Fixed** — added cooperative stop flag and `LaunchPad.stop()` case for `bug1.1`.

---

## 4. High Findings

### H-1: LaunchPad.stop() calls start() instead of stop() for bug2 and bug9
- **File:** `src/com/buggyapp/LaunchPad.java` (lines 277, 339)
- **Impact:** Stop requests **restart** demos — scheduler overlap, inability to recover via web UI.
- **Severity:** High | **Confidence:** High
- **Status:** **Fixed**

### H-2: CPUSpikeDemo.stop() uses deprecated Thread.stop() on wrong instance
- **File:** `src/com/buggyapp/cpuspike/CPUSpikeDemo.java` (line 43)
- **Impact:** Unsafe thread termination; stop ineffective (new thread instance).
- **Severity:** High | **Confidence:** High
- **Status:** **Fixed** — relies on existing `CPUSpikerThread.setFlag(false)` from `LaunchPad.stop()`

### H-3: HTTP connection leak (sequential variant)
- **File:** `HttpConnectionLeakSimulator.start()` — 1000 iterations, never disconnects.
- **Status:** Intentional demo. Document only.

### H-4: Infinite file reader leak
- **File:** `FileConnectionLeak.java` — `BufferedReader` never closed.
- **Status:** Intentional demo (`bug10`). Document only.

### H-5: 200-thread pool writing 100K files each without shutdown
- **File:** `fileleak/FileLeakDemo.java`
- **Status:** Intentional demo (`bug17`). Document only.

### H-6: Unbounded thread spawn per proxy connection
- **File:** `netwrorkslowness/DelayedProxy.java`
- **Status:** Intentional demo. Document only.

### H-7: Memory amplification + 200 concurrent slow network clients
- **File:** `SlowNetworkClientExample.java` — clones 500KB payload 5000× per request.
- **Status:** Intentional demo (`bug20`). Document only.

### H-8: Static unbounded cache without backpressure
- **File:** `sampleapp/SampleAppDemo.java` — `ConcurrentHashMap` with 5 producers, 1 consumer clearing every 60s.
- **Status:** Intentional demo (`sample`). Document only.

### H-9: Infinite CPU busy-loop
- **File:** `CPUSpikerThread.run()` — tight spin while flag true.
- **Status:** Intentional demo (`bug3`). Document only.

### H-10: Integer.MAX_VALUE exception loop
- **File:** `ExceptionsDemo.start()` — divide-by-zero in loop.
- **Status:** Intentional demo (`bug9`). Stop flag added for lifecycle control.

### H-11: Infinite allocation + forced GC
- **File:** `GcDemo.start()` — `System.gc()` each iteration.
- **Status:** Intentional demo (`bug21`). Document only.

### H-12: Finalizer thread exhaustion
- **File:** `SlowFinalizeDemo` / `slowfinalize/Object1.finalize()` — 3-minute sleep in finalizer.
- **Status:** Intentional demo (`bug2`). Stop flag added.

### H-13 through H-20
Additional high findings (OOM demos, logging in loops, H2 50M batch mismatch, blocked threads, deadlock, heavy I/O, Netty sleep on I/O thread, Docker 1-hour connection timeout, exponential Fibonacci) — all documented as intentional demos or config review items. See Medium/Low sections for operational items.

### H-21: Docker connection timeout 3600000ms (1 hour)
- **File:** `Dockerfile` line 20
- **Impact:** Hung connections hold threads for extended periods in deployed mode.
- **Severity:** High | **Confidence:** High
- **Status:** Document only — may be intentional for long-running chaos sessions. Human review recommended.

---

## 5. Medium/Low Findings

### Medium

| ID | Finding | File | Confidence | Action |
|----|---------|------|------------|--------|
| M-1 | Log4j2 `status="debug"` adds internal diagnostic overhead | `resources/log4j2.xml` | High | **Fixed** → `WARN` |
| M-2 | Async appender without explicit `bufferSize` | `log4j2.xml` | Medium | **Fixed** → `bufferSize="8192"` |
| M-3 | Size-based log rollover commented out | `log4j2.xml` | Medium | **Fixed** → 250 MB policy enabled |
| M-4 | Disk fill opens new FileOutputStream per MB | `DiskSpaceService.java` | High | Document only (intentional) |
| M-5 | NetworkLag proxy restart without drain guard | `NetworkLagService.java` | Medium | Document only |
| M-6 | No auth/rate-limit on `/launch-buggyapp` | `web.xml`, servlet | High | Document only (intentional for demo) |
| M-7 | SQL string concatenation for table name | `DBConnectionLeak.java` | High | Document only |
| M-8 | Deprecated Thread.stop pattern (other demos) | Various | Medium | Partially fixed in CPUSpikeDemo |
| M-9 | ArraysDemo counter never incremented (infinite loop) | `inefficientlist/ArraysDemo.java` | High | Document only — likely teaching bug |

### Low

| ID | Finding | File | Action |
|----|---------|------|--------|
| L-1 | ArrayList without initial capacity | `ListWithOutCapacityDemo.java` | Document only |
| L-2 | StringBuilder without initial capacity | `JustString.java` | Document only |
| L-3 | Per-call object allocation waste | `RandomExample.java` | Document only |
| L-4 | ThrottledInputStream single-byte read path | `ThrottledInputStream.java` | Document only |
| L-5 | Metaspace leak demo disabled (commented) | `MetaspaceLeakProgram.java` | N/A |
| L-6 | build.xml test classpath references log4j 2.6.2 while runtime uses 2.24.3 | `build.xml` | Document only |

---

## 6. Suggested Fix Plan

### Phase 1 — Guardrails (applied in PR)
1. Fix inverted start/stop in `LaunchPad.stop()` for `bug2`, `bug9`
2. Add cooperative stop flags to demos missing them (`SlowFinalizeDemo`, `ExceptionsDemo`, `memoryleakthread.MapManager`)
3. Remove unsafe `Thread.stop()` from `CPUSpikeDemo.stop()`
4. Tune Log4j2: `status=WARN`, bounded async queue, size-based rollover
5. Remove unused servlet request retention

### Phase 2 — Human review (do not auto-fix)
1. Replace servlet thread-per-request with bounded `ExecutorService` + rate limiting
2. Add authentication on `/launch-buggyapp` for non-dev deployments
3. Reduce Docker `connectionTimeout` from 3600000ms to 30–60s if acceptable for demo duration
4. Align `build.xml` test classpath Log4j versions with runtime 2.24.3

### Phase 3 — Intentional demos (no fix)
Do not modify core simulation logic (memory leaks, connection leaks, N+1, CPU spin, etc.) — these are the product's purpose.

---

## 7. Runtime Validation Checklist

- [ ] `ant clean && ant dist-ee` succeeds on JDK 8+
- [ ] `ant testng` — servlet tests pass (`LaunchBuggyAppServletTest`)
- [ ] Start WAR locally; POST `/launch-buggyapp?buggyAppType=PROBLEM_CPU&flag=true` — CPU spikes
- [ ] POST same with `flag=false` — CPU spike stops (verify via `top`/APM)
- [ ] POST `buggyAppType=bug2&flag=false` — finalizer demo stops (previously restarted)
- [ ] POST `buggyAppType=bug1.1&flag=false` — thread memory leak stops
- [ ] Monitor `${logDir}/logs/buggyapp.log` — no Log4j internal debug noise at startup
- [ ] Under load test of `/launch-buggyapp`, watch thread count — bounded executor still recommended
- [ ] Docker: verify app starts on port 9010; evaluate connection timeout behavior under hung client

---

## 8. Created Pull Requests

| PR | Branch | Description |
|----|--------|-------------|
| *(created by this run)* | `cursor/backend-performance-review-1df9` | Lifecycle stop-handler fixes, Log4j guardrails, servlet request retention removal |

Previous automation runs:
- [PR #1](https://github.com/Resul92/buggyapp/pull/1) — branch `cursor/performance-resource-review-227f`
- [PR #2](https://github.com/Resul92/buggyapp/pull/2) — branch `cursor/performance-resource-review-9de7`

---

## 9. Open Questions / Required Human Review

1. **Servlet threading model:** Should `/launch-buggyapp` use a shared bounded executor? What max pool size and queue depth are acceptable for demo deployments?
2. **Docker connection timeout:** Is 1 hour intentional for long chaos sessions, or should it be reduced to prevent thread tie-up?
3. **Security boundary:** Is unauthenticated access to chaos triggers acceptable in all deployment environments?
4. **H2ChattinessDemo scale:** Comment says 10K rows but loop runs 50M — is this intentional escalation or a documentation/implementation mismatch?
5. **Demo vs. production guardrails:** Should stop/lifecycle fixes be extended to all 20+ scenarios (many CLI-only demos lack stop handlers)?
6. **Build toolchain:** Cloud agent environment lacks `ant`; confirm CI runs `ant testng` on PR merge.

---

*Report generated by Cursor Automation Agent — scheduled performance resource review.*
