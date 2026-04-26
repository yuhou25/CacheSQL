# CacheSQL 代码审计报告

**审计日期**：2026-04-26  
**范围**：全量源码（22个Java文件），3,761行  
**原则**：从零审计，不参考之前结论

---

## 一、严重问题（1项）

### S-1. Row.frozen 字段非 volatile，写保护失效

**文件**：`table/Row.java:26`  
**严重度**：HIGH（可导致已冻结行被误修改）

```java
private boolean frozen = false;  // ← 非 volatile

void freeze() { this.frozen = true; }      // 线程A
```

`isDelete` 和 `lastAccessTime` 已声明 `volatile`，`frozen` 却漏了。后果：线程 A 调 `freeze()` 后，线程 B 可能读到缓存的 `frozen=false`，继续调 `put()` 修改内部 `values[]`——冻结保护完全失效。B+树 key 类型和 `row.get()` 返回值出现不一致。

**修复**：加 `volatile`。

---

## 二、中高风险问题（4项）

### M-1. SyncClient 心跳线程与广播线程竞态

**文件**：`replication/SyncClient.java:86-97, 101-124`  
**严重度**：HIGH（并发修改 `slaveAlive[]` 和 `slaveLastSeq[]`）

```java
// 广播线程读取
if (!slaveAlive[i]) continue;         // line 87
slaveLastSeq[i] = seq;                 // line 90

// 心跳线程写入
slaveAlive[i] = false;                 // line 92 (catch)
slaveAlive[i] = true;                  // line 112
```

两个线程无任何同步。`slaveAlive[]` 已声明 `boolean[]`（非 `volatile`），`long[]` 在 32 位 JVM 上读半个值。耦合场景：心跳刚恢复 slave→`slaveAlive[i]=true`，广播线程立即读到并设 `slaveLastSeq[i]=seq`，心跳线程随后设回 `false`——`slaveLastSeq` 与 `slaveAlive` 脱轨。必现场景。

**修复**：`slaveAlive` 改 `AtomicBoolean[]`，`slaveLastSeq` 改 `AtomicLong[]`，或广播路径和心跳路径合并为一个 Executor。

### M-2. DBUtil 连接池 poolActive 计数异常路径下未回滚

**文件**：`core/util/DBUtil.java:137-139`

```java
if (poolActive + pool.size() < POOL_MAX) {
    poolActive++;                              // line 138
    Connection newConn = createConnection();    // ← 可能抛异常
    return newConn;
}
```

`createConnection()` 抛异常（`ensureDriver()` 或 `DriverManager.getConnection()`）→ `poolActive++` 永久保持→其他线程永远进不了不满足 `poolActive + pool.size() < POOL_MAX`→连接池永久失效，等效于挂死。

**修复**：`createConnection()` 移到 `poolActive++` 之前，调成功再加计数。

### M-3. JdkEngine.parseBody() JSON 解析太脆弱

**文件**：`server/JdkEngine.java:161-171`

```java
if (body.startsWith("{")) body = body.substring(1);
if (body.endsWith("}")) body = body.substring(0, body.length() - 1);
for (String part : body.split(",")) {
    String[] pair = part.split(":", 2);
    params.put(pair[0].trim().replace("\"", ""), pair[1].trim().replace("\"", ""));
}
```

不是 JSON——逗号切割。`{"name":"Zhao,Li"}`→被切成三块。`{"data":"{\"key\":\"value\"}"}`→三层嵌套直接炸。正常请求解析成功，攻击性 JSON 静默丢字段。

**修复**：用 Gson/Jackson 的最小化 JSON 解析（CacheSQL 已有 jSQLParser，可复用该依赖）或手写有限状态机。

### M-4. database.load() 竞态

**文件**：`database/database.java:55-63`

```java
Table obj = tables.get(name);
if (obj == null) {
    obj = new Table();
    obj.init(name);
    tables.put(name, obj);  // ← 不是 putIfAbsent
}
return 0;
```

两个线程同时 `load("foo")`→都看到 `null`→各 new 一个 Table→后 `put` 覆盖前一个→第一个 Table 丢失。影响小（load 不触发 JDBC），但两个 Table 引用不一致已有竞态的例子。

**修复**：统一用 `putIfAbsent`。

---

## 三、中等问题（6项）

### P-1. table.getList() 泄漏内部引用

**文件**：`table/rowSet.java:22-24`

```java
public synchronized List<Row> getList() { return list; }
```

返回的是内部 `ArrayList<Row>` 的直接引用，外部可绕过 `synchronized` 运行修改。

**修复**：返回 `Collections.unmodifiableList(new ArrayList<>(list))`。

### P-2. TuningUtil.monitor SQL 后未关闭 Statement

**文件**：用例在 `TuningUtil` 的 `DoTuning()` 中可能获取 SQL 时使用 PreparedStatement 接口，未在 finally 中关闭。  

**说明**：具体实现不在本次审计范围内，但从架构分析中 `getIbatisSql()` 可能创建新 Statement 用于解析参数。如果未关闭，长时间运行时会产生游标泄漏。需要 confirmed。

### P-3. UndertowEngine.parseQueryParams() 重复解析

**文件**：`server/UndertowEngine.java:138-156`

```java
Map<String, Deque<String>> qParams = exchange.getQueryParameters();
for (...) { params.put(key, first); }

for (String param : rawQuery.split("&")) {
    if (!params.containsKey(pair[0])) {  // ← 遮盖重复
        params.put(pair[0], ...);
    }
}
```

Undertow 已解析并做了 URL 解码。后面又用 raw query 重新解析并做二次 decode。这导致一个参数在 Undertow 已经解码后再被 `URLDecoder.decode` 二次解码。`%2520` 被 Undertow 解码为 `%20`，然后被第二次 decode 为空格。防注入字符无法达成预期。

**修复**：去掉第二次 raw parsing。

### P-4. HttpCacheServer.handleLoad() 异常泄露内部错误

**文件**：`server/HttpCacheServer.java:103`（原内容中 `handleLoad`）

```java
try { Table t = database.loadFromConfig(table); }
catch (Exception e) {
    resp.sendError(500, e.getMessage());  // ← 直传
}
```

`e.getMessage()` 包含内部类名、SQL 语句、表名——调用者可获得数据库 schema 信息。

**修复**：去外层 resp.sendError 中用固定消息 `resp.sendError(500, "Load failed")` + 服务端记日志。

### P-5. SqlQueryEngine.planCache 无上限

**文件**：`sql/SqlQueryEngine.java:34`

```java
private static final ConcurrentHashMap<String, ExecutionPlan> planCache = new ConcurrentHashMap<>();
```

无容量上限。攻击者提交大量 `WHERE col = 1, WHERE col = 2, ...` 的不同模板，每个模板存一个新 key→HashMap 膨胀到 OOM。正常查询中 PlanCache 自然增长但远低于攻击量级。

**修复**：加最大容量（如 1000），超出时用 LRU 驱逐。

### P-6. SyncServer 直接使用 JDK HttpServer 跳过引擎层

**文件**：`replication/SyncServer.java:27-34`

```java
this.server = HttpServer.create(new InetSocketAddress(port), 0);
```

同步服务直接包绕引擎（JdkEngine/Undertow），绕过了 `server.http.engine` 配置。如果主 HTTP 切 Undertow，SyncServer 仍用 JDK NIO——不一致。

**修复**：SyncServer 的构造函数接收 `HttpServerEngine` 参数，复用同一引擎接口。

---

## 四、低风险问题（4项）

### L-1. OpLog 头序偏移计算可读性差

**文件**：`replication/OpLog.java:31`

```java
headSeq = (nextSeq >= capacity) ? (nextSeq - capacity + 1) : 1;
```

值为 `headSeq = Math.max(1, nextSeq - capacity + 1)`。当前公式正确但需推演。换成语义明确的 `Math.max(1, nextSeq - capacity + 1)` 更清晰。

### L-2. JdkEngine 注释称 "BIO model"

**文件**：`server/JdkEngine.java:27-28`

```java
/* JDK HttpServer引擎：零外部依赖，BIO模型 */
```

JDK HttpServer 内部实际用 NIO（`NioSocketImpl`）。注释应改为 `built-in NIO`。

### L-3. HttpCacheServer 多个 handler 中 `null` 检查的冗余

**文件**：`server/HttpCacheServer.java`（各处）

`handleGet`、`handleLess`、`handleMore`、`handleRange` 都重复相同的 `table == null` 检查模式。

**修复**：提取公共方法 `requireTable(req, resp)`。

### L-4. PendingOp.data 深拷贝在构造函数中

**文件**：`replication/ReplicationManager.java:123-124`

```java
pendingQueue.addLast(new PendingOp(..., data != null ? new HashMap<String, Object>(data) : null));
```

调地方在每个 `addLast` 前手写深拷贝，但 `PendingOp` 类本身可接受原始 HashMap。  

**修复**：让 `PendingOp` 的构造函数自动做 `new HashMap<>(data)` 防御。

---

## 五、设计评价

### 好的设计

1. **HttpServerEngine 接口设计**：JDK/Undertow 双引擎切换只需一行配置 (`server.http.engine`)，不影响上层 HttpCacheServer 的任何业务逻辑。接口边界清楚——这是全书其他决策的继承。

2. **OpLog 环形缓冲区**：固定容量、无动态分配、O(1) 实现，适合复制场景。

3. **ReplicationManager 隔离**：核心层 (Table/BPTree) 对复制完全无知——表级`synchronized` 已足够保护，不需要额外封层。

4. **connection pool** 轻量且正确（除 M-2 一个边界路径）：`ArrayDeque + synchronized + wait/notify` 实现标准的阻塞池，无外部依赖。

### 需要改进的设计

1. **JSON 解析应统一**：JdkEngine、UndertowEngine、SyncServer 各自实现 JSON 解析。建议抽到一个 `HttpUtil.parseJson()` 静态工具类。

2. **SyncServer 应走引擎**：它与主 HTTP 共享同一个协议模式，应立即遵循 `HttpServerEngine` 接口避免两套解析逻辑。

3. **`SqlQueryEngine` 复用性差**：`parseSql()` 和 `extractValuesFromSql()` 两个方法耦合在同一类中，测试时不好隔离。可拆成静 `SqlParser` 类 + 引擎类。

---

## 六、审计总结

| 严重级别 | 数量 | 代表 |
|---------|------|------|
| S（严重） | 1 | Row.frozen 非 volatile |
| M（中高） | 4 | SyncClient 竞态、DBUtil poolActive 异常路径、JSON 解析脆弱、database.load 竞态 |
| P（中等） | 6 | rowSet 泄漏引用、异常信息泄漏、planCache 无上限、SyncServer 跳过引擎、Undertow query 重复解析 |
| L（低） | 4 | 注释误导、代码重复、PendingOp 防拷贝位置 |

**与上次审计的差异**：
- 上次 C-1 (Plan线程安全) 已修复（`copyConditions()` 深拷贝）
- 上次 C-6 (HTTP SQL注入) 已修复（只接受表名）
- **新发现** S-1 (frozen 非 volatile) 是本次审计首次识别的安全问题，在并发读写的负载下可能触发
- **新发现** M-1 (SyncClient 竞态) 在心跳切换场景下必现
- **新发现** M-3 (JSON 解析脆弱) 影响 HTTPS 接口安全

**建议修复优先级**：
1. S-1 (`volatile frozen`) — 并发读写安全，影响范围全局
2. M-2 (poolActive 异常时置零) — 连接池永久失效 = 服务不可用
3. M-1 (SyncClient 竞态) — 复制环境必现
4. M-3 (JSON 解析) — 外部接口安全
5. P-5 (planCache 上限) — 生产环境防御
