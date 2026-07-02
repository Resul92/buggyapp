# Performance Resource Review - buggyapp - 2026-07-02

## 1. Executive Summary

BuggyApp is an intentional Java chaos/performance simulation application. Most severe CPU, RAM, thread, file descriptor, socket, database, and GC pressure patterns are product behavior used to demonstrate failures such as memory leaks, CPU spikes, deadlocks, thread leaks, DB connection leaks, file handle leaks, and slow backends.

High-confidence, low-risk fixes were limited to guardrails that do not change the demo scenarios:

- Log4j2 internal status logging now uses `WARN` instead of `debug`.
- Log4j2 rolling files now have a 250 MB size-based rollover policy in addition to daily rollover.
- `LaunchBuggyAppServlet.BuggyAppThread` no longer retains the unused `HttpServletRequest`.

Broader behavior such as bounded executors, per-demo single-flight locking, endpoint authentication, Docker heap defaults, and demo stop-path redesign were documented for human review because they affect product semantics or operational policy.

## 2. Repository/Stack Detected

- Repository: `Resul92/buggyapp`
- Branch reviewed: `cursor/performance-resource-review-9de7`
- Runtime stack: Java 8 source/target, Servlet 2.4, JSP, embedded Tomcat `webapp-runner.jar`
- Build: Apache Ant (`build.xml`)
- Main web entry point: `/launch-buggyapp` mapped to `com.buggyapp.servlet.LaunchBuggyAppServlet`
- CLI entry point: `com.buggyapp.LaunchPad`
- Runtime packaging: WAR, CLI JAR, enterprise zip, Docker image
- Logging: Log4j2 async appender with rolling file and console appenders
- Schedulers/consumers: none found
- Workers/background execution: ad-hoc Java threads and fixed thread pools inside demo classes

## 3. Critical Findings

### Critical: Intentional resource-exhaustion demos

- **Files/classes:** Most classes under `src/com/buggyapp/**`, especially `memoryleak`, `cpuspike`, `threadleak`, `dbconnectionleak`, `fileleak`, `httpconnectionleak`, `chattiness`, `gc`, and `deadlock`.
- **Evidence:** README describes the application as a simulator for memory leaks, `OutOfMemoryError`, CPU spikes, thread leaks, `StackOverflowError`, deadlocks, and unresponsiveness. `LaunchPad` dispatches scenarios such as `PROBLEM_OOM`, `PROBLEM_CPU`, `PROBLEM_THREADLEAK`, `DB_CONNECTIONS_LEAK`, `HTTP_CONNECTION_LEAK`, and `GC_PAUSE`.
- **Severity:** Critical
- **Confidence:** High
- **CPU/RAM impact:** These scenarios intentionally consume CPU, heap, native threads, file descriptors, sockets, database connections, disk, or GC capacity.
- **Recommended fix:** Do not remediate wholesale without a product decision; these are the core training behaviors.
- **Validation method:** Run each scenario in an isolated environment and observe expected resource metrics (`jcmd`, thread dumps, heap/GC logs, `lsof`, DB connection counts, disk usage).

### Critical: Servlet request path can amplify chaos scenarios

- **File:** `src/com/buggyapp/servlet/LaunchBuggyAppServlet.java`
- **Function/class:** `doProcess()` and inner `BuggyAppThread`
- **Evidence:** Each valid web request creates a new worker thread that calls `LaunchPad.start()` or `LaunchPad.stop()`. Existing comments note this should eventually be converted to an executor.
- **Severity:** Critical under concurrent access
- **Confidence:** High
- **CPU/RAM/thread impact:** Repeated clicks or concurrent clients can start overlapping copies of resource-heavy demos, multiplying CPU, heap, thread, disk, and connection pressure.
- **Recommended fix:** Human-reviewed design for bounded executor, per-demo single-flight state, endpoint authorization, duplicate-start handling, and explicit stop semantics.
- **Validation method:** Load test `/launch-buggyapp` with concurrent POSTs and monitor JVM thread count, heap, CPU, and demo-specific resources.
- **Decision:** Documented only for concurrency redesign; a narrow no-behavior-change request-retention fix was applied.

## 4. High Findings

### High: Background worker retained `HttpServletRequest`

- **File:** `src/com/buggyapp/servlet/LaunchBuggyAppServlet.java`
- **Function/class:** `BuggyAppThread`
- **Evidence:** The worker stored `HttpServletRequest request` but never used it in `run()`.
- **Severity:** High
- **Confidence:** High
- **RAM impact:** Long-running demo threads could retain container request/session state longer than needed.
- **Recommended fix:** Pass only extracted values into the worker.
- **Validation method:** Static check that `BuggyAppThread` has no `HttpServletRequest` field and constructor no longer accepts the request.
- **Status:** Fixed.

### High: Docker runtime defaults reserve a large heap and keep idle connections for one hour

- **File:** `Dockerfile`
- **Config evidence:** `-Xms2g`, `-Xmx4g`, and `-AconnectionTimeout=3600000`
- **Severity:** High
- **Confidence:** High
- **CPU/RAM/thread impact:** Starts with a 2 GB heap commitment, allows up to 4 GB heap, and can hold slow/idle connector threads for up to one hour.
- **Recommended fix:** Expose JVM heap and connection timeout via environment variables or deployment configuration; consider container-aware RAM percentage defaults.
- **Validation method:** Run with constrained container memory and slow-client tests.
- **Decision:** Documented only; changing heap/timeout defaults can change demo behavior and operator expectations.

### High: Fixed thread pools and unbounded loops in CLI/demo scenarios

- **Files/classes:** `HttpConnectionLeakSimulator`, `FileLeakDemo`, `SlowNetworkClientExample`, `DelayedProxy`, `ChattinessDemo`, `H2ChattinessDemo`, `ThreadLeakDemo`, `MapManager`, `GcDemo`
- **Severity:** High
- **Confidence:** High
- **CPU/RAM impact:** 200-thread pools, unbounded allocation, no socket disconnects, no network/DB timeouts, and tight loops intentionally create CPU, RAM, connection, GC, and file descriptor pressure.
- **Recommended fix:** Keep as demo behavior; document isolation requirements and avoid exposing these workloads outside controlled environments.
- **Validation method:** Scenario-specific resource monitoring in an isolated lab.
- **Decision:** Documented only.

## 5. Medium/Low Findings

### Medium: Log4j2 internal status logger used debug

- **Files:** `resources/log4j2.xml`, `webroot/WEB-INF/classes/log4j2.xml`
- **Evidence:** `<Configuration status="debug">`
- **Severity:** Medium
- **Confidence:** High
- **CPU/logging impact:** Extra Log4j internal status output can increase stderr/container log noise.
- **Recommended fix:** Use `status="WARN"`.
- **Validation method:** XML parse and static check.
- **Status:** Fixed.

### Medium: Rolling file policy had no active size cap

- **Files:** `resources/log4j2.xml`, `webroot/WEB-INF/classes/log4j2.xml`
- **Evidence:** Only `TimeBasedTriggeringPolicy` was active; `SizeBasedTriggeringPolicy` was commented out.
- **Severity:** Medium
- **Confidence:** High
- **Disk/logging impact:** Busy days can produce a very large active log file before daily rollover.
- **Recommended fix:** Enable `SizeBasedTriggeringPolicy size="250 MB"`.
- **Validation method:** XML parse and static check.
- **Status:** Fixed.

### Medium: Ant test classpath referenced older Log4j2 jars

- **File:** `build.xml`
- **Evidence:** Test classpath referenced `log4j-core-2.6.2.jar` and `log4j-api-2.6.2.jar`, while compile classpath and Eclipse metadata reference `2.24.3`.
- **Severity:** Medium
- **Confidence:** High
- **Impact:** Test/runtime mismatch and possible failed test setup on clean checkouts.
- **Recommended fix:** Align the test classpath to Log4j2 `2.24.3`.
- **Validation method:** Static check and Ant test execution when dependency jars are available.
- **Decision:** Documented only; the change is build-hygiene rather than a direct performance guardrail and should be handled with dependency restoration/testing.

### Low: Embedded launch script backgrounds the JVM

- **File:** `embedded-runner/launch.sh`
- **Evidence:** `java ... &`
- **Severity:** Low to Medium
- **Confidence:** High
- **Impact:** Process management and signal handling can be confusing outside Docker.
- **Recommended fix:** Consider `exec java ...` or use an init wrapper if this script is used under supervision.
- **Validation method:** Start/stop script under a supervisor and verify signal propagation.
- **Decision:** Documented only; may affect existing user workflow.

## 6. Suggested Fix Plan

Applied in this review:

1. Keep intentional chaos scenarios unchanged.
2. Remove unused request retention from the servlet worker.
3. Harden Log4j2 status logging and size rollover in both source and generated-classpath copies.
4. Verify with static guardrail checks and XML parsing.

Recommended for human review:

1. Design a bounded executor and per-demo single-flight model for `/launch-buggyapp`.
2. Decide whether `/launch-buggyapp` needs authentication or network isolation guardrails.
3. Decide whether Docker heap and connection-timeout defaults should be environment-configurable.
4. Review stop-path semantics for demos where stopping currently starts or cannot stop work.
5. Add runtime validation scripts for each demo scenario in isolated environments.

## 7. Runtime Validation Checklist

- Run `ant testng` once dependency jars are available in `webroot/WEB-INF/lib`.
- Start the WAR and POST to `/launch-buggyapp` for each web-exposed scenario.
- During `PROBLEM_CPU`, verify CPU rises on start and falls after stop.
- During `PROBLEM_THREADLEAK`, verify thread count rises and stop flag prevents new thread creation.
- During memory and GC scenarios, capture heap/GC metrics with `jcmd`, JMX, or GC logs.
- During file/socket/DB scenarios, monitor `lsof`, `/proc/<pid>/fd`, DB connection count, and disk usage.
- For logging changes, generate sustained logs and confirm active files roll at 250 MB or daily, whichever comes first.
- For Docker, run with explicit memory limits and observe JVM heap sizing and connector behavior.

## 8. Created Pull Requests

- Pending: a Cursor Automation Agent pull request will be opened from branch `cursor/performance-resource-review-9de7` after verification.

## 9. Open Questions / Required Human Review

- Should the chaos endpoint be restricted by authentication, network policy, or deployment-only controls?
- Should repeated starts of the same demo be rejected, coalesced, or allowed for stress testing?
- What concurrency limit is acceptable for web-triggered demos?
- Are Docker `-Xms2g -Xmx4g` defaults intentional for training material, or should container-aware defaults be used?
- Should CLI-only scenarios be exposed via the web UI, or remain CLI-only?
- Should stop paths be redesigned so every scenario is idempotently stoppable?
