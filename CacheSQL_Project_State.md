# CacheSQL 项目状态快照

**保存时间**: 2026-04-26  
**工作目录**: `D:\work\myMemoryDB`（计划改名为 `CacheSQL`）

---

## Goal
- Build an in-memory database cache system (CacheSQL) with B+Tree indexes, HTTP API, SQL query engine, master-slave replication, and production hardening.

## Constraints & Preferences
- Package: `com.browise`, Maven project, JDK 1.8 minimum, verified on JDK 8/11/17/21
- Project renamed from `myMemoryDB` to `CacheSQL` — artifactId, jar name (`CacheSQL-1.0-SNAPSHOT.jar`), all docs, logs, and code references updated
- Oracle DB at `jdbc:oracle:thin:@192.168.70.26:1521:orcl`, user `core/core`
- MySQL and PG drivers in pom.xml (optional), commented examples in config.properties
- External config: `config.properties` in working directory (or `-Dconfig=xxx`), overrides via `-D` system properties
- Read-heavy, write-light: social insurance "个人查询", point queries and small result sets
- Reads need NO locking — `synchronized` only on write methods
- JDK HttpServer over Spring Boot — zero dependency; Undertow (NIO) engine added as optional upgrade
- Embedded-first, HTTP only for ops
- Production data: KCA2 table 1,001,425 rows (≈100万), 31 columns, 3 indexes
- Row objects returned by reference (zero-copy), frozen after loading
- Write operations (insert/update/delete) are `synchronized`
- B+ tree nodes pre-allocated to order capacity (256), inspired by Oracle PCTFREE
- Soft delete design: `isDelete=true` flag, Row stays in ArrayList, compact() for reclamation
- Master-slave replication: no sharding, each node holds full dataset, OpLog for incremental sync
- Replication layer does NOT modify core layer (Table/BPTree/Node/Row/SqlQueryEngine)
- HTTP SQL injection eliminated: SQL loaded from config.properties only, not HTTP params
- PreparedStatement with `?` bind params from config (`cache.table.X.params`)

---

## Architecture

### Source Files (21 files)

| Module | Files | Purpose |
|--------|-------|---------|
| core | DBUtil.java, utilException.java | Config loading, connection pool, utilities |
| table | Table.java, Row.java, rowSet.java | In-memory table, row storage, soft delete |
| btree | BPTree.java, Node.java, Tree.java | B+ tree index engine |
| index | CompositeKey.java | Composite index key |
| server | HttpCacheServer.java, HttpServerEngine.java, JdkEngine.java, UndertowEngine.java, JsonParser.java | REST API, engine abstraction (BIO/NIO), JSON parser |
| replication | ReplicationManager.java, OpLog.java, SyncClient.java, SyncServer.java | Master-slave replication |
| query | SqlQueryEngine.java | SQL parsing + index execution |
| root | Main.java | Entry point |

### Key Interfaces/Patterns

- **HttpServerEngine** interface: `start(port, threads)`, `stop()`, `registerRoute(path, handler)`
  - `JdkEngine`: BIO, zero dependency, deferred route registration (routes stored in list, registered in `start()`)
  - `UndertowEngine`: NIO, configurable ioThreads/workerThreads/bufferSize/maxEntitySize
  - Config: `server.http.engine=jdk|undertow`
- **ReplicationManager**: Static, role-based routing (standalone/master/slave)
  - standalone=pass-through, master=local+log+broadcast, slave=forward to master
- **Request/Response**: Inner classes of HttpServerEngine, decoupled from specific HTTP library

---

## Progress

### Done
- **21 source files**, ~5000 lines; **13+ test files**, ~1500 lines
- **53 unit tests pass** (0 failures)
- **6/6 integration tests pass**: Full master-slave lifecycle verified
- **Production audit**: 50 issues identified and fixed
- **Code audit #2**: 15 issues found, 13 fixed, 1 not applicable (P-2 TuningUtil), 1 deferred (P-6 SyncServer engine)

### Bugs Found and Fixed (total 7)

| # | Severity | Description | Fix |
|---|----------|-------------|-----|
| 1 | High | `extractDataParams()` excluded `value` param | Auto-include index column value |
| 2 | Medium | `loadFromConfig()` unconditionally called `init()` (JDBC) | Skip when `sql.equals(name)` |
| 3 | Medium | `Table` missing `setColumnNames()` | Added |
| 4 | Low | `forwardToMaster()` type mismatch | `String.valueOf()` |
| 5 | High | `toKeyForColumn()` didn't respect `String` colType when value is Number | Added `colType == String.class` branch |
| 6 | High | `Row.frozen` non-volatile (audit S-1) | Added `volatile` |
| 7 | High | `SyncClient` heartbeat/broadcast race on `slaveAlive[]` (audit M-1) | `synchronized(aliveLock)` |

### Audit #2 Fixes Applied

| Issue | Fix Applied |
|-------|-------------|
| S-1: Row.frozen non-volatile | `volatile boolean frozen` |
| M-1: SyncClient race | `synchronized(aliveLock)` on all `slaveAlive[]` reads/writes |
| M-2: DBUtil poolActive not rolled back on exception | `createConnection()` before `poolActive++` |
| M-3: JSON parser splits on commas | New `JsonParser.java` with finite state machine |
| M-4: database.load(String) race | `putIfAbsent()` |
| P-3: UndertowEngine double query parsing | Removed raw query re-parsing, use Undertow's `getQueryParameters()` only |
| P-4: Exception leaks internal info | Engine wrappers: fixed message `"Internal Server Error"` + server log |
| P-5: planCache unbounded | `PLAN_CACHE_MAX=1024`, `clear()` on overflow |
| L-1: OpLog headSeq formula readability | `Math.max(1, nextSeq - capacity + 1)` |
| L-3: Handler null check redundancy | Extracted `getTableOrError()` shared method |
| L-4: PendingOp deep copy | Constructor auto-copies `new HashMap<>(data)` |

### Audit #2 Deferred

| Issue | Reason |
|-------|--------|
| P-1: rowSet.getList() leaks reference | Performance concern; returns direct ArrayList ref for speed. Mark as internal API. |
| P-2: TuningUtil Statement leak | **Not applicable** — TuningUtil class does not exist in project |
| P-6: SyncServer skips engine layer | Internal sync protocol, not exposed to apps. Acceptable complexity tradeoff. |
| L-2: JDK BIO comment | API semantics are BIO even if JDK internally uses NIO. No change. |

---

## Performance Benchmarks

### Embedded Mode
- Read: 1.54M QPS (16-thread get), 916K QPS (16-thread SQL)
- Write: 719K QPS (16-thread update), 142K QPS (16-thread insert)

### HTTP Mode — BIO vs NIO (8 threads, production recommended)

| Metric | BIO (JDK) | NIO (Undertow) | NIO Advantage |
|--------|-----------|----------------|---------------|
| SQL read | 13,161 QPS | 20,744 QPS | +58% |
| /get read | 14,297 QPS | 22,929 QPS | +60% |
| range read | 12,941 QPS | 19,368 QPS | +50% |
| update | 15,328 QPS | 24,701 QPS | +61% |
| insert | 15,783 QPS | 23,427 QPS | +48% |
| mixed r/w | 15,009 QPS | 22,379 QPS | +49% |

---

## Configuration

### Engine Parameters

| Config | BIO | NIO | Default | Description |
|--------|-----|-----|---------|-------------|
| `server.http.engine` | `jdk` | `undertow` | `jdk` | Engine selection |
| `server.http.threads` | ✅ pool size | — | `CPU*2` | BIO thread pool |
| `server.http.backlog` | ✅ TCP backlog | — | `0` | BIO TCP queue |
| `server.http.ioThreads` | — | ✅ NIO selectors | `max(2,CPU)` | NIO IO threads |
| `server.http.workerThreads` | — | ✅ workers | `ioThreads*8` | NIO worker threads |
| `server.http.bufferSize` | — | ✅ buffer | `16384` | NIO per-connection buffer |
| `server.http.maxEntitySize` | — | ✅ max body | `1048576` | NIO max request body |

### Replication Parameters
- `server.role=standalone|master|slave`
- `server.sync.port=19091`, `server.slaves=...`, `server.master.url=...`
- `server.oplog.capacity=10000`, `server.heartbeat.interval=5000`
- `server.pending.capacity=5000`, `server.pending.flush.interval=2000`

---

## Key Decisions
- Slave forwards writes to master instead of rejecting (403) — application doesn't need to know who is master
- SyncServer replays via `table.insert()` directly, NOT ReplicationManager — prevents forwarding loop
- pendingQueue overflow: FIFO, drops oldest when full (`server.pending.capacity=5000`)
- insert is upsert (idempotent): guarantees pendingQueue replay safety
- `database.loadFromConfig()` skips JDBC when `sql.equals(name)`, supports `cache.table.X.columns`
- `extractDataParams()` auto-includes index column value in data map
- Connection pool: lightweight ArrayDeque implementation, no external dependency
- PreparedStatement for all JDBC: `bindParam()` auto-detects Long > Double > String
- Soft delete over hard delete: O(1), no ArrayList shift, no rowIdx invalidation; compact() for reclamation
- `removeFromIndexesWithValues()` uses `Integer.valueOf(rowIdx)` for object equality
- OpLog ring buffer: `(seq - 1) % capacity` position calculation
- SyncClient broadcast via single-thread Executor: guarantees op ordering
- ReplicationManager is the ONLY write entry point for applications
- Read path unchanged: bypasses replication layer entirely
- JDK HttpServer for zero dependency; Undertow optional via `server.http.engine`
- HttpServerEngine interface: deferred route registration (routes stored in list, registered in `start()`)
- `toKeyForColumn()`: explicit `String.class` branch to prevent Number→Long type mismatch

---

## Critical Implementation Details
- **`extractIndexValues` ordering**: MUST be called before `data.delete()` / after `row.setDelete(false)` because `Row.get()` returns null when `isDelete=true`
- **B+ tree value list is shared reference**: `tree.get(key)` returns actual ArrayList; `remove(Integer.valueOf(rowIdx))` modifies in-place
- **Slave write path**: App → ReplicationManager → HTTP forward master → master executes + broadcasts → SyncServer → `table.insert()` (core layer, no ReplicationManager, no loop)
- **ReplicationManager static final**: ROLE/masterUrl are `static final` from config at class-load time; cannot change role at runtime or in unit tests
- **Test helpers exposed**: `flushPendingPublic()`, `clearPending()`, `getPendingCount()`, `isMasterReachable()` for testing
- **Master restart limitation**: New JVM has empty tables; pendingQueue only replays ops during outage
- **`Node.remove` break fix (H-11)**: Added `break` after first matching child in non-leaf routing loop
- **`Row.frozen` now volatile**: S-1 fix ensures freeze() visible across threads
- **SyncClient `slaveAlive[]` access**: All reads/writes wrapped in `synchronized(aliveLock)`
- **`JsonParser`**: Finite state machine parser, handles commas/colons in values; used by both JdkEngine and UndertowEngine

---

## Documentation
- `docs/CacheSQL_功能说明书.md`: Architecture, modules, replication, fault tolerance
- `docs/CacheSQL_部署手册.md`: Hardware, JDK, memory, config (BIO/NIO params), master/slave deployment, Q&A
- `docs/CacheSQL_操作手册.md`: REST API, Java embed API (Chapter 9), troubleshooting
- `docs/CacheSQL_测试报告.md`: 53 unit tests, 6 integration tests, BIO vs NIO benchmarks, 7 bugs found
- `docs/CacheSQL_代码审计报告.md`: 15 issues (1S/4M/6P/4L), 13 fixed, 1 N/A, 1 deferred

---

## Test Files
- `BPTreeTest.java` (15 tests), `SqlQueryEngineTest.java` (11), `RowSetTest.java` (9)
- `InsertUpdateDeleteTest.java` (8), `ReplicationTest.java` (8), `ConcurrencyTest.java` (2)
- `FullBenchmark.java`, `HttpQpsBenchmark.java`, `WriteBenchmark.java`
- `EngineBenchmark.java` — BIO vs NIO full comparison (read + write, 5 thread counts)
- `GetFailDebug.java`, `GetDebug.java` — debug tests for /get endpoint

---

## Next Steps
- ~~Consider adding initial full data sync (slave pulls snapshot from master on startup)~~ → 已实现：启动时自动加载 `database.loadAllFromConfig()`
- ~~Consider persistence for pendingQueue (currently in-memory, lost on slave crash)~~ → 不需要：缓存层，数据源在关系库，可 reload
- ~~Directory rename from `myMemoryDB` to `CacheSQL`~~ → 已完成
- ~~Consider read-write lock instead of `synchronized` for higher concurrent write throughput~~ → 已实现：rowSet 7 个读方法去除 synchronized，get() 16线程从 154万→320万 QPS
- **集群水平扩展（表级分区）**：多组主从各装不同表集合，应用透明路由
  - 设计原则：向后兼容（不配集群信息时行为不变）、应用透明（应用只连一个 URL）、配置驱动
  - 配置项：`cluster.enabled`、`cluster.node.id`、`cluster.peer.<id>.tables`、`cluster.peer.<id>.url`
  - 请求逻辑：本地有表→直接处理；本地无表→查 cluster.peer 转发到目标节点
  - 不分片，每组内表全量，复制逻辑不变
  - 适用场景：单机内存不足，按业务模块（社保/医保/就业）分表到不同节点组
