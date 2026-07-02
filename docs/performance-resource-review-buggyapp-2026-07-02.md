# Performance Resource Review - buggyapp - 2026-07-02

> Prepared by Cursor Automation Agent.
>
> Note: no Confluence or Atlassian publishing tool was available in this environment, so this Confluence-format page is committed as a repository Markdown artifact. If a Confluence integration is provided later, this content can be copied directly into a Confluence page.

## 1. Executive Summary

`buggyapp` is a Java 8 / Servlet 2.4 chaos-demo application packaged with Ant as a WAR and runnable through `webapp-runner.jar` or a CLI `Main-Class`. The repository is intentionally designed to demonstrate JVM/backend failure modes: CPU spikes, memory leaks, GC pressure, blocked threads, deadlocks, file/HTTP/DB connection leaks, disk fill, network lag, and chatty database access.

Because most dangerous code paths are deliberate training scenarios, broad behavior-changing fixes were not applied. One low-risk operational guardrail was applied: Log4j internal status logging was reduced from `debug` to `WARN`, and size-based rolling was enabled for both runtime Log4j config copies to reduce disk-fill risk from unbounded active log files.

Highest operational risks if this app is deployed outside an isolated lab:

- Public servlet endpoint `/launch-buggyapp` creates an unmanaged thread per request and can launch resource-exhaustion demos.
- Several start paths create unbounded heap, native thread, file descriptor, socket, DB-connection, disk, and CPU pressure.
- Docker/runtime configuration uses a fixed 2-4 GiB heap and a 1-hour Tomcat connection timeout.
- Logging previously had time-only rollover with no active-file size cap.

## 2. Repository/Stack Detected

| Area | Evidence |
| --- | --- |
| Language/runtime | Java source compiled as Java 8 via `build.xml:51`; Docker runtime uses `adoptopenjdk/openjdk11:debian` in `Dockerfile:2`. |
| Build/package | Ant build (`build.xml`) creates `buggyapp.war` and `buggyApp.jar`; CLI entry point is `com.buggyapp.LaunchPad` at `build.xml:139-145`. |
| Web framework | Servlet 2.4 webapp, not Spring Boot. `webroot/WEB-INF/web.xml:20-27` maps `LaunchBuggyAppServlet` to `/launch-buggyapp`. |
| Runtime server | `webapp-runner.jar` launched in Docker and `embedded-runner/launch.*`. |
| Logging | Log4j2 (`resources/log4j2.xml`, `webroot/WEB-INF/classes/log4j2.xml`) with async file+console appender. |
| Database | Direct JDBC/H2/MySQL demos using `DriverManager`; no repository-wide connection pool or JNDI datasource in `context.xml`. |
| Messaging/schedulers | No Kafka, message consumers, Spring schedulers, cron manifests, or Kubernetes manifests detected. |
| Primary entry points | `LaunchPad.main()` (`src/com/buggyapp/LaunchPad.java:41`), `LaunchPad.start/stop()` (`LaunchPad.java:226-360`), `LaunchBuggyAppServlet.doGet/doPost/doProcess()` (`src/com/buggyapp/servlet/LaunchBuggyAppServlet.java:35-75`). |

## 3. Critical Findings

### C1. Servlet endpoint spawns an unbounded raw thread per request

- **File path:** `src/com/buggyapp/servlet/LaunchBuggyAppServlet.java`
- **Function/class:** `LaunchBuggyAppServlet.doProcess()`, nested `BuggyAppThread`
- **Code evidence:** Lines 73-75 explicitly note a new thread is spawned for each request: `new BuggyAppThread(request, buggyAppTypeParam, booleanFlag).start();`. Lines 182-190 retain `HttpServletRequest` on the worker object.
- **Severity:** Critical
- **Confidence:** High
- **CPU/RAM impact:** Native thread exhaustion, heap growth from retained request state, and amplified CPU/RAM/FD pressure because each request can launch a demo workload.
- **Recommended fix:** Use a bounded `ExecutorService` with a limited queue, request deduplication, and access controls/rate limits around `/launch-buggyapp`.
- **Validation method:** Load-test concurrent requests to `/launch-buggyapp`; monitor JVM thread count with `jcmd <pid> Thread.print`, OS threads, request latency, and rejected-task metrics.

### C2. Unbounded heap growth in memory leak demo

- **File path:** `src/com/buggyapp/memoryleak/MapManager.java`
- **Function/class:** `MapManager.createObjects()`
- **Code evidence:** Lines 74-84 define a static `flag` and loop while true by default; lines 92-104 continuously insert large strings into `myMap` without eviction.
- **Severity:** Critical
- **Confidence:** High
- **CPU/RAM impact:** Heap exhaustion, long GC pauses, eventual `OutOfMemoryError`.
- **Recommended fix:** For production code, replace unbounded maps with bounded caches and explicit lifecycle cleanup. For this demo app, keep the behavior isolated behind strong access controls.
- **Validation method:** Run `bug1` with `-Xmx512m`; monitor heap occupancy and GC logs.

### C3. Continuous native thread leak

- **File path:** `src/com/buggyapp/threadleak/ThreadLeakDemo.java`, `src/com/buggyapp/threadleak/ForeverThread.java`
- **Function/class:** `ThreadLeakDemo.start()`, `ForeverThread.run()`
- **Code evidence:** `ThreadLeakDemo.start()` creates `new ForeverThread().start()` every 100 ms while `flag` is true (`ThreadLeakDemo.java:21-31`). Each `ForeverThread` sleeps in a loop for 10 minutes at a time (`ForeverThread.java:14-19`).
- **Severity:** Critical
- **Confidence:** High
- **CPU/RAM impact:** Native thread exhaustion and `OutOfMemoryError: unable to create new native thread`.
- **Recommended fix:** Use a bounded executor and task cancellation; never create unbounded threads in a loop.
- **Validation method:** Run `bug4`; monitor process thread count with `jcmd`, `jstack`, or OS thread metrics.

### C4. Database connection leak without pool or close

- **File path:** `src/com/buggyapp/dbconnectionleak/DBConnectionLeak.java`
- **Function/class:** `DBConnectionLeak.leakConnection()`, `leakConnections()`
- **Code evidence:** `DriverManager.getConnection()` is called at lines 34-35 and line 51. The `finally` block comments out `closeConnection(connection)` at lines 61-63. `leakConnections()` loops while true at lines 74-77.
- **Severity:** Critical
- **Confidence:** High
- **CPU/RAM impact:** DB-side connection exhaustion, app-side socket/native memory pressure, and increased DB CPU from repeated physical connection setup.
- **Recommended fix:** Use a bounded connection pool such as HikariCP/Tomcat JDBC pool, try-with-resources, and explicit connection/query timeouts.
- **Validation method:** Run `bug11` against a test database with low max connections; monitor DB process/session count and app socket count.

### C5. File descriptor leaks from unclosed readers, streams, and channels

- **File path:** `src/com/buggyapp/fileconnectionleak/FileConnectionLeak.java`, `src/com/buggyapp/fileleak/FileLeakSimulator.java`, `src/com/buggyapp/fileleak/FileChannelLeakSimulator.java`
- **Function/class:** `FileConnectionLeak.connect()`, `FileLeakSimulator.start()`, `FileChannelLeakSimulator.start()`
- **Code evidence:** `FileConnectionLeak.connect()` opens `BufferedReader` and never closes it (`FileConnectionLeak.java:24-38`). `FileLeakSimulator.start()` keeps every `FileInputStream` in `openFiles` forever (`FileLeakSimulator.java:22-32`). `FileChannelLeakSimulator.start()` opens up to 1,000,000 channels without closing them (`FileChannelLeakSimulator.java:19-31`).
- **Severity:** Critical
- **Confidence:** High
- **CPU/RAM impact:** File descriptor exhaustion, kernel memory pressure, and filesystem/inode pressure.
- **Recommended fix:** Use try-with-resources and bounded handle counts; add monitoring on open FD counts.
- **Validation method:** Lower `ulimit -n`, run `bug10`/`bug17`/`bug18`, and monitor `lsof -p <pid>` or `/proc/<pid>/fd`.

### C6. HTTP socket leak and no network timeouts

- **File path:** `src/com/buggyapp/httpconnectionleak/HttpConnectionLeakSimulator.java`
- **Function/class:** `HttpConnectionLeakSimulator.start()`, `start1()`
- **Code evidence:** `start()` opens `HttpURLConnection` and an `InputStream` without closing/disconnecting (`HttpConnectionLeakSimulator.java:21-35`). `start1()` creates a fixed 200-thread pool, submits 10,000 tasks, loops 10,000 times per task, and comments out `conn.disconnect()` (`HttpConnectionLeakSimulator.java:39-55`). No connect/read timeouts are configured.
- **Severity:** Critical
- **Confidence:** High
- **CPU/RAM impact:** Socket/ephemeral-port exhaustion, thread saturation, remote call pileups, and retry-like request storms against `httpbin.org`.
- **Recommended fix:** Use a shared HTTP client with bounded connection pool, connect/read timeouts, try-with-resources, and bounded task submission.
- **Validation method:** Run `bug19` in a controlled environment; monitor `ss -tan`, `lsof -i`, thread pool size, and request error rates.

## 4. High Findings

### H1. Tight CPU spin loop

- **File path:** `src/com/buggyapp/cpuspike/CPUSpikerThread.java`, `src/com/buggyapp/cpuspike/CPUSpikeDemo.java`
- **Function/class:** `CPUSpikerThread.run()`, `CPUSpikeDemo.start()`
- **Code evidence:** `CPUSpikerThread.run()` loops on `while (flag)` and calls an empty method with no sleep/backoff (`CPUSpikerThread.java:18-21`). `CPUSpikeDemo.start()` starts multiple spiker threads (`CPUSpikeDemo.java:27-31`).
- **Severity:** High
- **Confidence:** High
- **CPU/RAM impact:** One saturated CPU core per spiker thread.
- **Recommended fix:** Replace busy loops with blocking queues, sleeps/backoff, or bounded worker pools.
- **Validation method:** Run `bug3`; monitor CPU with `top`, JFR, or async-profiler.

### H2. Chatty/N+1 database access and excessive loop logging

- **File path:** `src/com/buggyapp/chattiness/ChattinessDemo.java`
- **Function/class:** `ChattinessDemo.start()`, `getUsersByIds()`
- **Code evidence:** Starts 100 threads at lines 53-56. Each thread builds nearly 1,000,000 IDs (`lines 32-35`), then loops forever (`line 37`), calls one query per ID (`lines 91-106`), and prints every result (`line 39`).
- **Severity:** High
- **Confidence:** High
- **CPU/RAM impact:** DB query storm, CPU overhead from per-row round trips, large per-thread lists, and console/log I/O flood.
- **Recommended fix:** Batch or page IDs, use `IN`/joins, cap thread count, and replace per-item logging with sampled metrics.
- **Validation method:** Run `bug16` briefly in test; monitor DB query rate, heap, CPU, and stdout/log throughput.

### H3. H2 chattiness demo performs extreme inserts and selects

- **File path:** `src/com/buggyapp/chattiness/H2ChattinessDemo.java`
- **Function/class:** `H2ChattinessDemo.start()`, `setupDatabase()`
- **Code evidence:** `start()` executes 50,000,000 single-row selects (`lines 17-31`) with CPU work per row. `setupDatabase()` comments "10,000 rows" but loops to 50,000,000 inserts (`lines 43-51`).
- **Severity:** High
- **Confidence:** High
- **CPU/RAM impact:** CPU saturation, very large in-memory H2 table, long GC pauses, and possible heap exhaustion.
- **Recommended fix:** Bound row counts, page queries, and avoid in-memory databases for high-volume simulation unless heap is sized intentionally.
- **Validation method:** Run with GC logs and H2 memory metrics; compare with a bounded row count.

### H4. Heavy disk I/O and disk-fill scenarios

- **File path:** `src/com/buggyapp/io/IOThread.java`, `src/com/buggyapp/diskspace/DiskSpaceService.java`
- **Function/class:** `IOThread.run()`, `DiskSpaceService.fillDiskSpace()`
- **Code evidence:** `IOThread.run()` repeatedly writes and reads files while `flag` is true (`IOThread.java:46-64`). `DiskSpaceService.fillDiskSpace()` writes 1 MiB chunks until a requested percentage of usable disk is filled (`DiskSpaceService.java:22-44`).
- **Severity:** High
- **Confidence:** High
- **CPU/RAM impact:** Disk throughput saturation, ephemeral storage exhaustion, inode pressure, and latency for other workloads sharing the same volume.
- **Recommended fix:** Require isolated temp volumes, quotas, cleanup, and rate limits for demo scenarios.
- **Validation method:** Run `bug8`/`bug12` on a disposable volume; monitor `df -h`, `iostat`, and application latency.

### H5. Forced GC and retained allocations in an infinite loop

- **File path:** `src/com/buggyapp/gc/GcDemo.java`
- **Function/class:** `GcDemo.start()`
- **Code evidence:** Infinite `while (true)` allocates byte arrays into `memoryHog` and calls `System.gc()` each iteration (`GcDemo.java:19-36`).
- **Severity:** High
- **Confidence:** High
- **CPU/RAM impact:** Heap growth, repeated full-GC pressure, and stop-the-world pauses.
- **Recommended fix:** Do not call `System.gc()` in hot paths; clear/reuse buffers and put hard caps on retained allocations.
- **Validation method:** Run `bug21` with GC logging; observe full GC frequency and heap occupancy.

### H6. Exception storm

- **File path:** `src/com/buggyapp/exceptions/ExceptionsDemo.java`
- **Function/class:** `ExceptionsDemo.start()`
- **Code evidence:** Loop to `Integer.MAX_VALUE`, dividing by zero and catching the exception each iteration (`ExceptionsDemo.java:10-21`).
- **Severity:** High
- **Confidence:** High
- **CPU/RAM impact:** CPU overhead from exception construction/handling and possible safepoint/GC pressure.
- **Recommended fix:** Use precondition checks and avoid exceptions for control flow.
- **Validation method:** Run `bug9`; capture JFR exception rate and CPU profile.

### H7. Exponential recursive CPU workload

- **File path:** `src/com/buggyapp/slowbackend/SlowBackendDemo.java`
- **Function/class:** `SlowBackendDemo.fibonacci()`
- **Code evidence:** Naive recursive Fibonacci for `FIB_NUMBER = 1000` (`SlowBackendDemo.java:5-15`, `30-33`).
- **Severity:** High
- **Confidence:** High
- **CPU/RAM impact:** Exponential CPU consumption and deep recursion.
- **Recommended fix:** Memoize, use iterative dynamic programming, or cap input size.
- **Validation method:** Time the method under a profiler; compare to iterative implementation.

### H8. Container starts with fixed large heap and one-hour connection timeout

- **File path:** `Dockerfile`
- **Function/class:** Container `ENTRYPOINT`
- **Code evidence:** Line 20 launches Java with `-Xms2g`, `-Xmx4g`, and `-AconnectionTimeout=3600000`.
- **Severity:** High
- **Confidence:** High
- **CPU/RAM impact:** Heap may exceed container limits or leave insufficient native memory; stalled clients can hold Tomcat sockets/threads for up to an hour.
- **Recommended fix:** Use configurable `JAVA_OPTS`, container-aware memory percentages, and a shorter connector timeout aligned with `maxThreads`/`acceptCount`.
- **Validation method:** Run the image with `--memory=2g`; load-test slow clients and monitor Tomcat thread/socket counts.

### H9. Log files had no size-based rollover

- **File path:** `resources/log4j2.xml`, `webroot/WEB-INF/classes/log4j2.xml`
- **Function/class:** Log4j `RollingFile` policies
- **Code evidence:** Before this review, each file used `TimeBasedTriggeringPolicy` while `SizeBasedTriggeringPolicy size="250 MB"` was commented out at line 12.
- **Severity:** High
- **Confidence:** High
- **CPU/RAM impact:** Active log file could grow to multi-GB between time rollovers, filling container disk and increasing I/O.
- **Recommended fix:** Enable size-based rollover and reduce internal status verbosity.
- **Validation method:** XML/build validation plus sustained INFO log generation to verify rollover.
- **Status:** Fixed in this branch.

## 5. Medium/Low Findings

### M1. Network proxy creates raw threads per accepted socket

- **File path:** `src/com/buggyapp/netwrorkslowness/DelayedProxy.java`
- **Function/class:** `DelayedProxy.start()`
- **Code evidence:** Infinite accept loop creates two raw threads for every client (`DelayedProxy.java:21-31`).
- **Severity:** Medium
- **Confidence:** High
- **CPU/RAM impact:** Thread and socket growth under concurrent proxy clients.
- **Recommended fix:** Use a bounded executor or non-blocking proxy implementation with connection limits.
- **Validation method:** Open many proxy connections and monitor thread count.

### M2. LittleProxy filter sleeps on request-processing path

- **File path:** `src/com/buggyapp/networklag/NetworkLagService.java`
- **Function/class:** `NetworkLagService.startNetworkLagProxy()`
- **Code evidence:** `clientToProxyRequest()` calls `Thread.sleep(delay)` (`NetworkLagService.java:34-40`).
- **Severity:** Medium
- **Confidence:** High
- **CPU/RAM impact:** Blocks proxy worker/event-loop threads, reducing throughput and causing connection pileups.
- **Recommended fix:** Use non-blocking delay/backpressure or isolate the lag simulation behind strict concurrency limits.
- **Validation method:** Load-test proxy throughput with increasing delay and inspect thread dumps.

### M3. Static long-lived map with producers outpacing consumer

- **File path:** `src/com/buggyapp/sampleapp/SampleAppDemo.java`, `Producer.java`, `Consumer.java`
- **Function/class:** `SampleAppDemo.s_map`, `Producer.run()`, `Consumer.run()`
- **Code evidence:** Static `ConcurrentHashMap` at `SampleAppDemo.java:18`; five producers loop forever and put large strings every 1 ms (`Producer.java:14-49`); consumer clears once per minute (`Consumer.java:18-28`).
- **Severity:** Medium
- **Confidence:** High
- **CPU/RAM impact:** Heap growth between clears; possible producer/consumer imbalance.
- **Recommended fix:** Use a bounded queue/map and backpressure.
- **Validation method:** Run `sample` with small heap and inspect heap histogram.

### M4. Stop/lifecycle paths are incomplete or unsafe

- **File path:** `src/com/buggyapp/LaunchPad.java`, `src/com/buggyapp/cpuspike/CPUSpikeDemo.java`, `src/com/buggyapp/memoryleak/MemoryLeakDemo.java`
- **Function/class:** `LaunchPad.stop()`, `CPUSpikeDemo.stop()`, `MemoryLeakDemo.stop()`
- **Code evidence:** `CPUSpikeDemo.stop()` calls deprecated `Thread.stop()` on a new thread object (`CPUSpikeDemo.java:41-44`). `MemoryLeakDemo.stop()` only prints a message (`MemoryLeakDemo.java:31-34`). `LaunchPad.stop()` starts the exception demo instead of stopping it (`LaunchPad.java:336-340`).
- **Severity:** Medium
- **Confidence:** High
- **CPU/RAM impact:** Operators may be unable to stop resource pressure once triggered.
- **Recommended fix:** Centralize workload lifecycle with interruptible workers and tracked handles.
- **Validation method:** Start and then stop each scenario through CLI/servlet; verify CPU/heap/thread counts return to baseline.

### M5. Finalizer abuse

- **File path:** `src/com/buggyapp/slowfinalize/Object1.java`, `src/com/buggyapp/slowfinalize/SlowFinalizeDemo.java`
- **Function/class:** `Object1.finalize()`, `SlowFinalizeDemo.start()`
- **Code evidence:** `finalize()` sleeps for 3 minutes (`Object1.java:12-18`) while `SlowFinalizeDemo.start()` creates objects forever (`SlowFinalizeDemo.java:11-15`).
- **Severity:** Medium
- **Confidence:** High
- **CPU/RAM impact:** Finalizer queue buildup and delayed object reclamation.
- **Recommended fix:** Avoid finalizers; use `Cleaner` or explicit resource ownership patterns.
- **Validation method:** Run `bug2` and inspect finalizer queue/heap.

### M6. File utility reads full files into memory

- **File path:** `src/com/buggyapp/util/FileUtil.java`
- **Function/class:** `FileUtil.read()`
- **Code evidence:** Reads all lines into a `StringBuilder` (`FileUtil.java:53-65`).
- **Severity:** Low to Medium
- **Confidence:** High
- **CPU/RAM impact:** Heap pressure when called repeatedly or against large files.
- **Recommended fix:** Stream processing or cap file size.
- **Validation method:** Run `PROBLEM_IO` against larger files and monitor allocation rate.

### L1. Session timeout retains state for 60 minutes

- **File path:** `webroot/WEB-INF/web.xml`
- **Function/class:** Servlet session config
- **Code evidence:** `<session-timeout>60</session-timeout>` at lines 9-11.
- **Severity:** Low
- **Confidence:** High
- **CPU/RAM impact:** Idle sessions can retain heap for one hour.
- **Recommended fix:** Lower timeout or keep the UI stateless.
- **Validation method:** Simulate many sessions and inspect session manager/heap metrics.

## 6. Suggested Fix Plan

1. Keep chaos scenarios isolated:
   - Require private network access, authentication, and rate limits for `/launch-buggyapp`.
   - Run only with explicit CPU, memory, PID, FD, and disk quotas.
2. Harden public entry points:
   - Replace servlet raw-thread spawning with a bounded executor and reject/queue policy.
   - Stop retaining `HttpServletRequest` in background workers.
3. Add resource bounds to runtime config:
   - Make JVM memory configurable with container-aware defaults.
   - Reduce Tomcat connection timeout and explicitly set connector limits.
   - Add health checks and graceful shutdown behavior.
4. For any production-like code reuse:
   - Replace raw `DriverManager` usage with a bounded pool.
   - Add HTTP/JDBC/socket timeouts.
   - Use try-with-resources for all I/O.
   - Replace unbounded maps/lists/queues with bounded structures and TTL/eviction.
5. Keep intentional demos documented:
   - Document which routes intentionally exhaust CPU/RAM/threads/FDs/disk.
   - Add validation scripts that assert cleanup or controlled failure in disposable environments.

## 7. Runtime Validation Checklist

- Build/test:
  - `ant testng`
  - `ant dist`
- Servlet/thread safety:
  - Load-test `/launch-buggyapp` with concurrent start/stop requests.
  - Capture thread dumps before/during/after tests.
- JVM memory:
  - Run scenarios with `-Xmx512m` and GC logging.
  - Inspect heap with `jcmd`, `jmap -histo`, or JFR.
- File descriptors and sockets:
  - Lower `ulimit -n` in a disposable environment.
  - Monitor `/proc/<pid>/fd`, `lsof`, `ss -tan`, and ephemeral port usage.
- Database:
  - Run DB leak/chatty demos only against test DBs with low connection caps.
  - Monitor connection count, query rate, CPU, and slow query logs.
- Disk/logging:
  - Generate sustained logs and verify size-based rollover.
  - Run disk-fill scenarios only on disposable mounted volumes with quotas.
- Container:
  - Run with explicit memory/PID/CPU limits.
  - Test slow clients against port 9010 and monitor connector thread/socket behavior.

## 8. Created Pull Requests

- Pending: logging rollover/status guardrail PR will be opened from branch `cursor/performance-resource-review-227f`.

## 9. Open Questions / Required Human Review

- Should this repo continue to behave as a deliberately dangerous chaos/training app, or should public servlet access be hardened by default?
- What deployment environment is expected for Docker: standalone lab VM, Kubernetes, or shared test cluster?
- What memory/CPU/PID/FD/disk limits should be documented as required guardrails?
- Is Confluence publishing available through a separate integration not exposed to this automation run?
