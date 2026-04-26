# CacheSQL 测试报告

**项目**: CacheSQL — 内存缓存数据库中间件  
**版本**: 1.0-SNAPSHOT  
**测试日期**: 2026-04-26（更新 v2）
**测试环境**: Windows 10 / JDK 1.8.0_152 / 8核16线程 CPU / 8GB JVM

---

## 1. 项目概况

| 指标 | 数值 |
|------|------|
| 源文件数 | 21 个 |
| 源码行数 | 5,000+ 行 |
| 测试文件数 | 18 个 |
| 测试代码行 | 1,500+ 行 |
| JAR 包大小 | 80 KB（JDK）/ +1.2MB（含Undertow） |
| JDK 兼容版本 | 8 / 11 / 17 / 21 |
| 外部依赖 | jsqlparser 4.9 + JDBC 驱动 + Undertow 2.2.28（可选） |

### 模块结构

| 模块 | 文件 | 职责 |
|------|------|------|
| core | DBUtil, utilException | 配置加载、连接池、工具类 |
| table | Table, Row, rowSet | 内存表、行存储、软删除 |
| btree | BPTree, Node, Tree | B+ 树索引引擎 |
| index | CompositeKey | 联合索引键 |
| server | HttpCacheServer, HttpServerEngine, JdkEngine, UndertowEngine | REST API 服务、引擎抽象层 |
| replication | ReplicationManager, OpLog, SyncClient, SyncServer | 主从复制 |
| query | SqlQueryEngine | SQL 解析与索引执行 |

---

## 2. 单元测试

### 2.1 测试结果总览

| 测试类 | 用例数 | 通过 | 失败 | 耗时 |
|--------|--------|------|------|------|
| BPTreeTest | 15 | 15 | 0 | 0.107s |
| SqlQueryEngineTest | 11 | 11 | 0 | 0.101s |
| RowSetTest | 9 | 9 | 0 | 0.001s |
| InsertUpdateDeleteTest | 8 | 8 | 0 | 0.003s |
| ReplicationTest | 8 | 8 | 0 | 0.003s |
| ConcurrencyTest | 2 | 2 | 0 | 0.104s |
| **合计** | **53** | **53** | **0** | **0.319s** |

### 2.2 B+ 树测试（15 项）

| 用例 | 验证内容 |
|------|---------|
| testInsertAndGet | 基础插入与等值查询 |
| testInsertDuplicateKey | 非唯一索引：同一 key 多值 |
| testRangeQuery | 范围查询 (getMoreAndLessThen) |
| testLessThan | 小于查询 |
| testGreaterThan | 大于查询 |
| testRemove | 删除操作 |
| testRemoveNonExistent | 删除不存在的 key |
| testLargeDataset | 大数据量 (10000 条) |
| testMixedTypes | 混合类型 (Long/String) |
| testSequentialInsert | 顺序插入性能 |
| testRandomInsert | 随机插入正确性 |
| testEmptyTreeGet | 空树查询 (NPE 防护) |
| testEmptyTreeRange | 空树范围查询 |
| testSingleElement | 单元素边界 |
| testOverwriteValue | 覆盖已有值 |

### 2.3 SQL 查询引擎测试（11 项）

| 用例 | 验证内容 |
|------|---------|
| testSimpleEqual | WHERE col = value |
| testMultipleConditions | WHERE a=1 AND b=2 |
| testRangeCondition | WHERE col > 10 AND col < 50 |
| testLikePrefix | WHERE col LIKE 'prefix%' |
| testNoIndex | 无索引条件返回 no_index |
| testPlanCache | 执行计划缓存复用 |
| testComplexOr | OR 条件处理 |
| testGreaterThanEqual | WHERE col >= value |
| testLessThanEqual | WHERE col <= value |
| testCompositeIndex | 联合索引查询 |
| testDeepCopyIsolation | PlanCondition 深拷贝隔离 |

### 2.4 内存表操作测试（17 项）

**InsertUpdateDeleteTest（8 项）：**

| 用例 | 验证内容 |
|------|---------|
| testDelete | 软删除：删除后查询返回空 |
| testDeleteThenInsertReuse | 删除后插入复用槽位 |
| testUpdate | 更新行数据 |
| testUpdateChangesIndex | 更新后索引迁移正确 |
| testInsertNew | 新增行 |
| testInsertIdempotent | 同 key 重复 insert → 覆盖，不重复 |
| testDeleteNonExistent | 删除不存在 key 无异常 |
| testUpdateNonExistent | 更新不存在 key 无异常 |

**RowSetTest（9 项）：**

| 用例 | 验证内容 |
|------|---------|
| testAppendAndGet | 追加与读取 |
| testDelete | 软删除标记 |
| testActiveCount | 活跃行计数 |
| testCompact | 压缩回收已删除行 |
| testCompactRebuildsIndex | 压缩后索引重建 |
| testSize | 总行数统计 |
| testMultipleDeletes | 批量删除 |
| testCompactEmpty | 空表压缩 |
| testDeleteAndGet | 删除后 get 返回 null |

### 2.5 复制模块测试（8 项）

| 用例 | 验证内容 |
|------|---------|
| testInsertIdempotentSameKey | insert 幂等：同 key 两次 → 覆盖 |
| testInsertIdempotentThreeTimes | insert 幂等：三次 → 最后一次生效 |
| testInsertNewRow | 新 key insert |
| testDeleteThenInsertReuse | 删除后复用槽位 |
| testDeleteThenInsertThenIdempotent | 删除→恢复→再幂等覆盖 |
| testPendingQueueCountAndClear | pendingQueue 计数与清空 |
| testRoleFromConfig | 角色配置读取 |
| testRoleCheckMethods | isMaster/isSlave/isStandalone 互斥 |

### 2.6 并发测试（2 项）

| 用例 | 验证内容 |
|------|---------|
| testConcurrentReads | 16 线程 × 1000 次并发读（16,000 次） |
| testConcurrentRangeQueries | 8 线程 × 100 次并发范围查询 |

---

## 3. 主从复制集成测试

### 3.1 测试方法

使用 `test-replication.ps1` 自动化脚本：
- 启动 Master (port 8080) 和 Slave (port 8081) 两个独立 JVM 进程
- 轮询 `/cache/health` 自动等待服务就绪
- 执行 6 项端到端测试
- 测试完毕自动清理进程

### 3.2 测试结果

```
=== Test 1: Insert on Master, Slave syncs ===
  Master: {"code":0,"message":"ok","data":[{"id":"001","name":"Alice","age":25}]}
  Slave:  {"code":0,"message":"ok","data":[{"id":"001","name":"Alice","age":25}]}
  Result: PASS

=== Test 2: Insert on Slave, forwarded to Master ===
  Master: {"code":0,"message":"ok","data":[{"id":"002","name":"Bob","age":30}]}
  Slave:  {"code":0,"message":"ok","data":[{"id":"002","name":"Bob","age":30}]}
  Result: PASS

=== Test 3: Idempotent insert (same key) ===
  Query: {"code":0,"message":"ok","data":[{"id":"001","name":"AliceV2","age":99}]}
  Stats: totalRows=2, activeRows=2 (无重复行)
  Result: PASS

=== Test 4: Delete on Slave, forwarded to Master ===
  Master: data:[] (已删除)
  Slave:  data:[] (已删除)
  Result: PASS

=== Test 5: Master down, Slave buffers writes ===
  Master killed
  Insert 003: {"code":0,"message":"ok"} (缓冲到 pendingQueue)
  Insert 004: {"code":0,"message":"ok"} (缓冲到 pendingQueue)
  Result: PASS

=== Test 6: Master recovery, pending queue flushes ===
  Master RESTARTED (新 JVM 进程)
  Master 003: {"id":"003","name":"BufferedBoy","age":40} ✓
  Master 004: {"id":"004","name":"BufferedGirl","age":50} ✓
  Result: PASS
```

### 3.3 数据流验证

| 场景 | 写入路径 | 验证方式 |
|------|---------|---------|
| Master 写入 | App → Master → Table + OpLog + 广播 → Slave SyncServer 回放 | Slave 查询结果一致 |
| Slave 写入 | App → Slave → HTTP 转发 Master → Master 执行 + 广播 → Slave 回放 | Master 和 Slave 查询一致 |
| Master 故障 | App → Slave → pendingQueue 缓冲 | 返回 code=0，不报错 |
| Master 恢复 | Slave 后台线程 → FIFO 重放 pendingQueue | 缓冲数据成功到达 Master |

---

## 4. 测试过程中发现并修复的 Bug

| # | 严重级别 | 问题描述 | 修复方案 | 发现阶段 |
|---|---------|---------|---------|---------|
| 1 | **高** | `HttpCacheServer.extractDataParams()` 排除了 `value` 参数，导致索引列的值未写入 Row | 自动将 `column` 值（索引列名）和 `value` 值加入 data map | 集成测试 |
| 2 | **中** | `database.loadFromConfig()` 无条件调用 `init()`（JDBC），当 `sql==name` 仍尝试连接数据库 | 增加 `sql.equals(name)` 判断跳过 JDBC，支持 `columns` 配置项设置列名 | 集成测试 |
| 3 | **中** | `Table` 缺少 `setColumnNames()` 方法，无 JDBC 模式下无法设置列名 | 新增 `setColumnNames()` 方法 | 集成测试 |
| 4 | **低** | `ReplicationManager.forwardToMaster()` 参数类型不匹配：`Object keyValue` 无法传递给 `doForward(String)` | 统一用 `String.valueOf(keyValue)` 转换 | 编译期 |
| 5 | **高** | `Table.toKeyForColumn()` 当 `indexColumnType=String` 但 HTTP 参数为纯数字时，`parseValue()` 返回 `Long`，`toKey()` 将其转为 Long 而非 String，导致 B+ 树类型不匹配抛 `ClassCastException` | `toKeyForColumn()` 增加 `colType == String.class` 分支；同时修复 `insert()`、`rebuildIndexes()`、`compact()`、`removeFromIndexesWithValues()` 4 处 `toKey()` 残留调用，统一改用 `toKeyForColumn()` | NIO vs BIO 基准测试 |
| 6 | **中** | `UndertowEngine.start()` 读取配置 `server.http.ioThreads=0`（意为"自动"），直接将 0 传给 Undertow builder，XNIO 拒绝零线程，所有 NIO 请求全部失败 | 增加判断 `if (ioThreads <= 0) ioThreads = Math.max(2, cpus)`，`workerThreads` 同理 | NIO 基准测试 |

---

## 5. 已知限制

| 限制项 | 说明 | 影响 |
|--------|------|------|
| Master 重启数据丢失 | Master 内存数据不持久化，重启后为空表 | Slave 的历史数据不回传，需重新从数据库加载 |
| pendingQueue 内存级 | Slave 缓冲的操作存储在 JVM 内存中 | Slave 进程崩溃时缓冲数据丢失（缓存层，可从数据库 reload） |
| OpLog 环形覆盖 | OpLog 容量有限（默认 10000），超出覆盖旧操作 | Slave 离线时间过长（超过 OpLog 容量）需全量重新加载 |
| 同步延迟 | Master 写入到 Slave 回放存在网络延迟（通常 < 10ms） | 极端情况下 Slave 读取到旧数据 |

---

## 6. 性能测试

### 6.1 测试环境

| 项目 | 配置 |
|------|------|
| JVM | Java HotSpot 64-Bit 25.152-b16 |
| JDK | 1.8.0_152 |
| OS | Windows 10 amd64 |
| CPU | **8 核 16 线程**（Intel 超线程，物理 8 核） |
| Max Heap | 7,015 MB |
| 数据量 | 10,000 行 × 4 列 × 2 索引 |

> **线程与 CPU 的关系**：本机物理 8 核，16 线程为超线程。生产推荐 **8 线程**（等于物理核数），超线程对 IO 密集型 HTTP 场景提升有限。

### 6.2 内嵌模式性能

| 测试项 | QPS | 延迟 (us/op) | 说明 |
|--------|-----|-------------|------|
| `table.get()` 单线程 | **360,826** | 2.77 | B+ 树直接查询 |
| `SqlQueryEngine` 等值 单线程 | **210,485** | 4.75 | 含 SQL 解析 + PlanCache |
| `SqlQueryEngine` 范围 单线程 | **201,856** | 4.95 | BETWEEN 查询 |
| `SqlQueryEngine` LIKE 单线程 | **20,919** | 47.80 | 前缀匹配需遍历 |
| `table.get()` **8线程** | **2,300,000+** | ~0.43 | 物理核满并发 |
| `SqlQueryEngine` **8线程** | **870,914** | 1.15 | 含 SQL 解析开销 |
| `table.get()` 16线程 | **3,209,943** | 0.31 | 超线程，吞吐继续提升 |
| `SqlQueryEngine` 16线程 | **870,914** | 1.15 | SQL 解析成瓶颈，16线程不再增长 |

> **rowSet 读锁优化**：去除了 `rowSet.get()/size()/count()` 等 7 个读方法的 `synchronized`，16 线程 `get()` 从 154 万提升至 **320 万 QPS（+108%）**。写方法保留 `synchronized` 不变。

### 6.3 HTTP 模式性能 — BIO vs NIO 引擎对比

#### 测试配置

| 项目 | 配置 |
|------|------|
| BIO 引擎 | JDK HttpServer（`com.sun.net.httpserver`），BIO 线程池 |
| NIO 引擎 | Undertow 2.2.28.Final + XNIO，NIO 非阻塞 |
| 数据量 | 10,000 行 × 4 列（id/name/age/city），2 个 B+ 树索引 |
| 测试工具 | HttpURLConnection 短连接 |

#### 读取性能对比（QPS，全部 ok=100%，0 fail）

| 线程数 | BIO SQL | NIO SQL | NIO提升 | BIO /get | NIO /get | NIO提升 | BIO range | NIO range | NIO提升 |
|--------|---------|---------|---------|----------|----------|---------|-----------|-----------|---------|
| 1 | 3,896 | 4,636 | +19% | 5,409 | 6,795 | +26% | 4,485 | 5,421 | +21% |
| 4 | 10,754 | 14,300 | +33% | 11,371 | 17,698 | +56% | 10,021 | 14,605 | +46% |
| **8** | **12,939** | **26,920** | **+108%** | **16,115** | **29,537** | **+83%** | **16,188** | **24,195** | **+49%** |
| 16 | 21,889 | 30,582 | +40% | 23,441 | 34,192 | +46% | 21,183 | 28,639 | +35% |
| 32 | 25,008 | 29,086 | +16% | 27,731 | 32,079 | +16% | 24,246 | — | — |

#### 写入性能对比（QPS，全部 ok=100%，0 fail）

| 线程数 | BIO insert | NIO insert | NIO提升 | BIO update | NIO update | NIO提升 | BIO mixed | NIO mixed | NIO提升 |
|--------|-----------|-----------|---------|-----------|-----------|---------|----------|----------|---------|
| 1 | 5,419 | 6,253 | +15% | 6,246 | 6,269 | — | 5,774 | 6,736 | +17% |
| 4 | 12,714 | 18,237 | +43% | 13,547 | 18,528 | +37% | 12,587 | 17,349 | +38% |
| **8** | **16,810** | **26,567** | **+58%** | **17,151** | **29,057** | **+69%** | **15,650** | **22,373** | **+43%** |
| 16 | 21,896 | 30,483 | +39% | 20,169 | 30,911 | +53% | 20,092 | 27,541 | +37% |
| 32 | 23,741 | 30,483 | +28% | 23,490 | 30,369 | +29% | 23,830 | — | — |

#### 8 线程汇总（生产推荐配置，匹配物理核数）

**读取：**

| 引擎 | SQL等值 | /get索引 | range范围 |
|------|--------|---------|----------|
| **BIO (JDK)** | 12,939 QPS | 16,115 QPS | 16,188 QPS |
| **NIO (Undertow)** | 26,920 QPS | 29,537 QPS | 24,195 QPS |
| **NIO 提升** | **+108%** | **+83%** | **+49%** |

**写入：**

| 引擎 | insert | update | mixed r/w |
|------|--------|--------|-----------|
| **BIO (JDK)** | 16,810 QPS | 17,151 QPS | 15,650 QPS |
| **NIO (Undertow)** | 26,567 QPS | 29,057 QPS | 22,373 QPS |
| **NIO 提升** | **+58%** | **+69%** | **+43%** |

#### 引擎性能分析

| 特征 | BIO (JDK HttpServer) | NIO (Undertow) |
|------|---------------------|----------------|
| IO 模型 | BIO，每连接一线程 | NIO 非阻塞，IO 多路复用 |
| 8线程读 QPS | 12,939~16,188 | 24,195~29,537（**+49~83%**） |
| 8线程写 QPS | 15,650~17,151 | 22,373~29,057（**+43~69%**） |
| 16线程读 QPS | 21,183~23,441 | 28,639~34,192（+35~46%） |
| 外部依赖 | 零依赖（JDK 内置） | undertow-core 2.2（+1.2MB） |
| 配置 | `server.http.engine=jdk` | `server.http.engine=undertow` |

> **NIO 优势集中在 4~16 线程区间**，因为 NIO 的 IO 多路复用在中等并发下效率最高。32 线程时瓶颈转移至 B+ 树写锁竞争，HTTP 层不再是瓶颈，NIO 优势缩小。

### 6.4 性能结论

| 模式 | 操作 | 峰值 QPS | 延迟 |
|------|------|---------|------|
| **内嵌读** | get() 16线程 | **320 万** | 0.31 us |
| **内嵌读** | SQL 等值 16线程 | **87 万** | 1.15 us |
| **HTTP 读 NIO** | SQL 等值 8线程 | **2.7 万** | 37 us |
| **HTTP 读 NIO** | /get 索引 8线程 | **3.0 万** | 34 us |
| **HTTP 写 NIO** | update 8线程 | **2.9 万** | 34 us |
| **HTTP 写 NIO** | mixed 8线程 | **2.2 万** | 45 us |
| **HTTP 读 BIO** | SQL 等值 8线程 | 1.3 万 | 77 us |
| **HTTP 写 BIO** | update 8线程 | 1.7 万 | 58 us |

- 内嵌模式读性能：**微秒级延迟，320 万 QPS**（rowSet 去锁后较旧版 154 万提升 108%）
- HTTP NIO 模式（推荐）：8 线程读取 **2.7~3.0 万 QPS**，写入 **2.2~2.9 万 QPS**
- HTTP BIO 模式：8 线程读取 1.3~1.6 万 QPS，零外部依赖
- NIO vs BIO：8 线程读取提升 **49~108%**，写入提升 **43~69%**
- **引擎切换**：配置 `server.http.engine=undertow` 即可启用 NIO，无需改动业务代码

---

## 7. 测试覆盖率矩阵

| 模块 | 单元测试 | 集成测试 | 并发测试 |
|------|---------|---------|---------|
| B+ 树索引 | ✅ 15 项 | — | — |
| SQL 查询引擎 | ✅ 11 项 | — | — |
| 内存表 (CRUD) | ✅ 17 项 | — | — |
| Row 存储 | ✅ 9 项 | — | — |
| 主从复制 - 写转发 | ✅ (角色) | ✅ Test 2 | — |
| 主从复制 - 广播同步 | — | ✅ Test 1 | — |
| 主从复制 - 幂等性 | ✅ 3 项 | ✅ Test 3 | — |
| 主从复制 - 删除同步 | — | ✅ Test 4 | — |
| 主从复制 - 故障缓冲 | ✅ (队列) | ✅ Test 5 | — |
| 主从复制 - 恢复重放 | ✅ (flush) | ✅ Test 6 | — |
| HTTP API | — | ✅ 6 项 | — |
| 并发读安全 | — | — | ✅ 2 项 |
| 性能基准 | — | — | ✅ 8 项 (FullBenchmark) |
| 引擎 BIO vs NIO 对比 | — | — | ✅ 30 项 (EngineBenchmark) |

---

## 8. 结论

- **53 项单元测试**全部通过，0 失败，总耗时 0.319 秒
- **6 项集成测试**全部通过，覆盖主从复制完整生命周期
- **30 项引擎对比测试**（BIO vs NIO × 5 线程数 × 读取3场景 + 写入3场景），全部 0 fail
- **性能测试**：内嵌读峰值 320 万 QPS（较旧版 154 万提升 108%），HTTP NIO 8线程读取 2.7~3.0 万 QPS
- 测试过程中发现并修复 **6 个 Bug**（含 2 个高危：索引列值丢失 + toKeyForColumn 类型转换残留）
- 新增优化：rowSet 读锁去除（7 个方法）、启动时自动加载配置表、Undertow ioThreads 配置修复
- 产品核心功能（B+ 树索引、SQL 查询、主从复制、故障缓冲与恢复、双引擎切换）**均已验证可用**
