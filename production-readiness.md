# myMemoryDB 生产环境差距分析

**审查日期**: 2026-04-24  
**审查范围**: 全部源码，共14个Java文件  
**问题总数**: 50个

---

## 一、严重问题（CRITICAL × 7）— 必须修复

### C-1. 执行计划缓存线程安全 — 查询结果串数据
**文件**: `SqlQueryEngine.java:61-75`

缓存的 `PlanCondition` 对象被多个线程**同时修改** `value` 字段。线程A查 `AAC001='001'`，线程B查 `AAC001='002'`，B会覆盖A的value → **A拿到B的数据**。无异常，静默数据错误。

```
query("... where AAC001 = '张三'")  // 线程A
query("... where AAC001 = '李四'")  // 线程B — 覆盖了A的value
// 线程A可能返回李四的数据
```

**修复方案**: 每次query()时深拷贝PlanCondition，或改为不可变对象。

---

### C-2. B+树零同步 — 并发写入导致树结构损坏
**文件**: `BPTree.java`, `Node.java` 全文

B+树的所有字段（entries、children、parent、previous、next）无任何同步。并发 `insertOrUpdate` 时，一个线程在分裂节点，另一个在遍历 → 丢失节点、循环引用、ArrayIndexOutOfBounds。

**当前场景**: 读多写少，数据加载后只读 → **生产中暂无风险**。但auto-refresh开启时有并发写入。

**修复方案**: 写操作加 `synchronized`，或用 `ReadWriteLock`（读不互斥、写互斥）。

---

### C-3. Table.load() 竞态 — 重复加载丢数据
**文件**: `database.java:25-30`

经典 check-then-act：两个线程同时 `load("kca2")` → 都看到 null → 各自创建Table → 一个被丢弃（含已加载的数据）。

**修复方案**: `putIfAbsent` + 检查返回值。

---

### C-4. 表注册后未初始化即对其他线程可见
**文件**: `database.java:29`, `HttpCacheServer.java:92-96`

`load()` 先把空Table放入map，然后才调 `init()`。其他线程在init完成前拿到空表 → NPE。

**修复方案**: 在 `init()` 完成后才放入map，或用原子引用交换。

---

### C-5. HTTP无认证 — 任何人可读写全部数据
**文件**: `HttpCacheServer.java` 全文

- 默认绑定 `0.0.0.0`（所有网卡）
- 无认证、无鉴权
- 可执行任意SQL（`/cache/load`的sql参数直传JDBC）
- 可truncate表、读取全部数据、列出所有SQL

**修复方案**: 如果用HTTP模式，至少加IP白名单 + token认证。推荐：只用embed模式，不开HTTP。

---

### C-6. HTTP可执行任意SQL — 数据库被攻击
**文件**: `HttpCacheServer.java:81-82`, `Table.java:67-72`

`/cache/load` 的sql参数直接传给 `Statement.executeQuery()`，无任何过滤。攻击者可注入 `UNION SELECT` 读取任意表，或修改数据。

**修复方案**: SQL白名单校验，或只允许SELECT，禁止多语句。

---

### C-7. 索引死记录累积 — 查询性能无限退化
**文件**: `Table.java:150-176, 494-506`

`evictLRU()` 和 `cleanExpired()` 只标记Row为deleted，**不清除B+树中的索引条目**。100万行淘汰90万后，查询仍遍历90万条死记录。

**修复方案**: 周期性索引重建，或淘汰时同步删除索引条目。

---

## 二、高风险问题（HIGH × 11）

| # | 问题 | 文件 | 影响 |
|---|------|------|------|
| H-1 | **内存泄漏**: deleted Row永不释放 | `rowSet.java:65` | OOM，maxSize形同虚设 |
| H-2 | **查询结果无上限**: range查1000万行直接OOM | `Table.java:178-315` | 大结果集拖垮JVM |
| H-3 | **Table操作同步混乱**: init/truncate是synchronized，其他方法不是 | `Table.java` 全文 | 并发查询与刷新冲突 |
| H-4 | **无连接池**: 每次load新建Connection | `DBUtil.java:74-92` | 耗尽数据库连接 |
| H-5 | **refreshAll不原子**: 多表刷新时看到一半新一半旧 | `database.java:99-131` | 数据不一致 |
| H-6 | **HTTP线程池不关闭**: stop()后线程泄漏 | `HttpCacheServer.java:63` | JVM无法正常退出 |
| H-7 | **异常信息泄露**: e.getMessage()直接返回客户端 | `HttpCacheServer.java` 多处 | 暴露数据库schema |
| H-8 | **/cache/list暴露全部SQL**: 含表名、列名、WHERE条件 | `HttpCacheServer.java:372` | 信息泄露 |
| H-9 | **配置缺省无校验**: db.driver为null时NPE | `DBUtil.java:79` | 启动失败无明确提示 |
| H-10 | **空树操作NPE**: truncate后查询崩溃 | `Node.java:52,54` | 清表后首次查询异常 |
| H-11 | **B+树remove缺break**: 删一个key可能删多个 | `Node.java:657-662` | 索引损坏 |

---

## 三、中等问题（MEDIUM × 16）

| # | 问题 | 影响 |
|---|------|------|
| M-1 | `rowSet.getList()` 暴露内部可变列表 | 外部可绕过同步修改 |
| M-2 | `Row.values[]` 非volatile | CPU缓存导致读到旧值 |
| M-3 | ResultSet未显式关闭 | 数据库游标泄漏 |
| M-4 | `estimateRowCount` 静默吞异常 | OOM保护失效 |
| M-5 | parseLong未catch NumberFormatException | HTTP 500而非400 |
| M-6 | SQL负数提取失败 | `age > -5` 结果错误 |
| M-7 | `rowSet.insert()` 不更新count/activeCount | 统计不准 |
| M-8 | 全部用 `System.out.println` 日志 | 无法过滤、轮转、审计 |
| M-9 | 无健康检查端点 | K8s/负载均衡无法探活 |
| M-10 | 无优雅停机 | 部署时请求被截断 |
| M-11 | Plan缓存无上限 | 攻击性SQL占满内存 |
| M-12 | HTTP任务队列无上限 | 高负载下OOM |
| M-13 | 刷新后Plan缓存不失效 | SQL查到旧schema |
| M-14 | Row.set/get无越界检查 | ArrayIndexOutOfBounds |
| M-15 | `getMoreAndLessThen` 返回不一致 | null vs 空列表 |
| M-16 | `Runtime.freeMemory()` 不准确 | 内存检查误判 |

---

## 四、低风险问题（LOW × 16）

| 类别 | 问题 |
|------|------|
| 安全 | CORS通配符、SQL在GET参数中暴露、无速率限制 |
| 运维 | 无请求日志、无缓存命中率统计、无慢查询统计 |
| 代码质量 | JSON解析脆弱、parseValue不支持浮点、utilException消息为null |
| 边界 | CompositeKey的null处理、rowSet越界、硬编码参数（树阶256等） |

---

## 五、生产就绪优先级路线图

### 第一阶段：必须修复（上线前）

| 优先级 | 问题 | 工作量 | 说明 |
|--------|------|--------|------|
| **P0** | C-1 Plan缓存线程安全 | 2h | 每次query深拷贝PlanCondition |
| **P0** | C-7 索引死记录清理 | 4h | 淘汰时删除索引条目，或周期重建 |
| **P0** | H-1 Row内存泄漏 | 2h | deleted Row定期从ArrayList移除 |
| **P1** | C-3/C-4 load竞态+提前发布 | 3h | 原子化load+init |
| **P1** | H-4 连接池 | 4h | 加HikariCP或自建轻量池 |
| **P1** | H-3 同步模型统一 | 4h | ReadWriteLock保护init/query/truncate |
| **P1** | H-2 查询结果上限 | 1h | 加maxRows参数，默认1000 |
| **P1** | H-10 空树操作防护 | 1h | get/remove前检查空entries |

### 第二阶段：建议修复（上线后一周内）

| 优先级 | 问题 | 工作量 |
|--------|------|--------|
| P2 | C-2 B+树写同步 | 6h |
| P2 | H-5 refreshAll原子化 | 4h |
| P2 | H-11 B+树remove break | 0.5h |
| P2 | M-8 日志框架 | 4h |
| P2 | M-9 健康检查端点 | 1h |
| P2 | M-10 优雅停机 | 2h |
| P2 | M-13 刷新后清Plan缓存 | 0.5h |

### 第三阶段：加固（按需）

| 优先级 | 问题 | 工作量 |
|--------|------|--------|
| P3 | C-5/C-6 HTTP安全加固 | 8h |
| P3 | M-11/M-12 缓存/队列上限 | 2h |
| P3 | H-6 线程池关闭 | 1h |
| P3 | H-7/H-8 信息泄露 | 2h |
| P3 | 运维指标（命中率、慢查询） | 4h |
| P3 | 速率限制 | 2h |

---

## 六、关于当前场景的风险评估

**当前部署方式**: `server.mode=embed`，无HTTP，应用内嵌调用。

| 风险 | 当前影响 | 严重程度 |
|------|---------|---------|
| **C-1 Plan缓存线程安全** | **直接影响** — 并发SQL查询会串数据 | **致命** |
| **C-7 索引死记录** | 无影响（不启用TTL/maxSize淘汰） | 低 |
| **H-1 Row内存泄漏** | 无影响（不启用淘汰） | 低 |
| **C-5/C-6 HTTP安全** | 无影响（不开HTTP） | 无 |
| **H-4 连接池** | 有影响（每次refresh新建连接） | 中 |
| **H-3 同步模型** | 有影响（refresh与查询并发时） | 中 |
| **C-2 B+树并发写** | 低影响（仅refresh时写入） | 低 |

**结论**: embed模式下，**C-1（Plan缓存线程安全）是唯一致命问题**，必须立即修复。其余问题在当前"加载后只读"场景下风险可控，但应在开启auto-refresh前修复。
