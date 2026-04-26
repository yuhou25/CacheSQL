# CacheSQL 功能说明书

## 1. 概述

CacheSQL 是一个面向政务信息化系统的**内存缓存数据库中间件**，定位为 Oracle（及 MySQL/PostgreSQL）的读缓存加速层。  
核心能力：将数据库表全量加载到 JVM 堆内存中，通过 B+树索引提供微秒级等值/范围查询，减轻后端数据库读压力。

适用场景：OLTP 读多写少、数据量百万级、对查询延迟敏感的系统。

---

## 2. 架构

```
┌─────────────────────────────────────────────────────────┐
│                    应用服务（Java 应用内嵌）              │
│  ┌─────────────────────────────────────────────────────┐│
│  │  SqlQueryEngine    │    ReplicationManager          ││
│  │  (SQL解析+索引执行) │    (复制模式控制)               ││
│  ├─────────────────────┼───────────────────────────────┤│
│  │  Table / rowSet / Row   │  BPTree / Node            ││
│  │  (内存表+行存储)        │  (B+树索引+节点)           ││
│  ├─────────────────────┴───────────────────────────────┤│
│  │  DBUtil（连接池+JDBC）                               ││
│  ├─────────────────────┬───────────────────────────────┤│
│  │  HttpCacheServer    │  SyncClient / SyncServer       ││
│  │  (REST API / 可选)   │  (复制同步 / 可选)             ││
│  └─────────────────────┴───────────────────────────────┘│
│                                                         │
│  配置文件: config.properties                            │
└─────────────────────────────────────────────────────────┘
                     │
                     ▼
         ┌─────────────────────┐
         │  后端数据库          │
         │  (Oracle/MySQL/PG)   │
         └─────────────────────┘
```

**两种运行模式**：
- **embed（内嵌模式）**：无 HTTP 端口，应用直接调用 Java API，零网络开销
- **all（HTTP 模式）**：启动内置 HTTP 服务，其他语言/跨进程可通过 REST API 访问

---

## 3. 核心模块

### 3.1 数据加载

- **启动自动加载**：`config.properties` 中配置的表定义（`cache.table.<name>.sql`）在服务启动时自动发现并加载，无需手动调用 `/cache/load`
- **全量加载**：通过 JDBC 执行配置中定义的 SQL，将结果集全部读入内存
- **PreparedStatement 支持**：SQL 可带 `?` 占位符，参数在 `config.properties` 中定义
- **加载前内存预检**：先执行 `COUNT(*)` 估算行数 → 预测内存消耗 → 不足则拒绝加载
- **行冻结机制**：加载完成后对每行调用 `freeze()`，防止应用层误修改内部数据
- **定时刷新**：通过 `cache.refreshInterval` 配置自动重新加载，原子替换表引用，刷新期间查询不中断

### 3.2 B+树索引

| 特性 | 说明 |
|------|------|
| 树阶 | 256（默认） |
| 非唯一索引 | key→ArrayList\<rowIdx\>，支持同一值多行 |
| 单列索引 | 等值/范围/前缀 LIKE |
| 联合索引 | `A+B` 语法，基于 CompositeKey |
| 内存结构 | 预分配 ArrayList，避免加载期频繁扩容 |
| 类型处理 | 自动检测列类型（Long/Double/String），查询参数自动转换 |

索引优先级：等值查询(EQ) > 范围查询(LT/GT/LE/GE)。

### 3.3 SQL 查询引擎

- 基于 **jsqlparser 4.9** 解析 SQL
- **执行计划缓存**：SQL 模板化（值替换为 `?`）→ 解析 → 缓存，同模板只解析一次
- 每次查询**深拷贝 PlanCondition**，防止多线程并发修改导致数据串读
- 支持条件：`=`、`<`、`>`、`<=`、`>=`、`LIKE 'prefix%'`
- **不支持**：JOIN（多表用视图）、`LIKE '%...'` 或 `LIKE '%...%'`
- 多条件时选取最优索引执行，剩余条件在内存中逐行过滤

### 3.4 内存表 (Table)

- **行存储**：`ArrayList<Row>`，下标即 rowIdx，B+树索引存储 rowIdx 定位
- **软删除**：`delete()` 只标记 `isDelete=true`，不移动 ArrayList 元素，O(1) 完成
- **insert 幂等性（upsert）**：insert 采用三段逻辑——主键存在且行已删除→复用槽位；主键存在且行活跃→覆盖更新；主键不存在→新增。重复调用不会产生重复行，保证复制重放的幂等性
- **TTL 淘汰**：按最后访问时间清除超期行
- **LRU 淘汰**：`maxSize` 限制行数，超限时扫描 100 行淘汰最久未访问的
- **查询结果上限**：默认最多返回 10000 行，防止大结果集 OOM
- **压缩**：`compact()` 移除已删除行并重建所有 B+树索引（rowIdx 变更后必须重建）
- **写入类型自适应**：HTTP 写入参数以 String 传输，系统根据索引列类型（Long/Double/String）自动转换，确保 B+树键匹配正确

### 3.5 REST API (HttpCacheServer)

基于 JDK 内置 `com.sun.net.httpserver.HttpServer`，零外部 HTTP 依赖。

| 端点 | 方法 | 功能 |
|------|------|------|
| `/cache/load` | POST | 按表名从配置文件加载缓存 |
| `/cache/query` | GET/POST | SQL 查询 |
| `/cache/get` | GET | 等值查询 |
| `/cache/less` | GET | 小于查询 |
| `/cache/more` | GET | 大于查询 |
| `/cache/range` | GET | 范围查询（between） |
| `/cache/composite` | GET | 联合索引查询 |
| `/cache/ttl` | POST | 设置 TTL |
| `/cache/maxSize` | POST | 设置最大行数 |
| `/cache/stats` | GET | 表统计信息 |
| `/cache/clean` | POST | 清理过期行 |
| `/cache/truncate` | POST | 清空表 |
| `/cache/list` | GET | 列出所有表 |
| `/cache/metrics` | GET | 内存和线程指标 |
| `/cache/refresh` | POST | 手动触发全部刷新 |
| `/cache/health` | GET | 健康检查 |
| `/cache/compact` | POST | 压缩已删除行 |
| `/cache/insert` | POST | 插入行 |
| `/cache/update` | POST | 更新行 |
| `/cache/delete` | POST | 删除行 |

### 3.6 复制模块

三种角色通过 `server.role` 配置：

| 角色 | 行为 |
|------|------|
| **standalone** | 单节点，写操作直接落本地 Table，零额外开销 |
| **master** | 本地执行写操作 → OpLog 记录 → 异步广播到所有 Slave |
| **slave** | 接收的写操作转发到 Master → Master 执行并广播 → Slave 的 SyncServer 接收后回放 |

**容错机制**：
- Slave 在 Master 不可达时，写操作缓存在 `pendingQueue`（FIFO，可配容量）
- 后台 `flushPending` 线程定期尝试重发，成功一个弹出一个，失败即停保持顺序
- 队列满时丢弃最旧操作（FIFO 溢出），可通过 `server.pending.capacity` 调整
- insert 的 upsert 语义保证重放不会产生重复数据
- Master 的 SyncClient 周期性心跳检测 Slave 存活
- Slave 恢复后，Master 从 OpLog 增量补发缺失操作（毫秒级）

**OpLog 环形缓冲区**：固定容量（默认 10000），覆盖最旧条目。

---

## 4. 数据流

### 4.1 查询流程

```
SQL 查询请求
    │
    ▼
SqlQueryEngine.query()
    │
    ├─ 模板化 SQL → 查 PlanCache
    │     ├─ 未命中 → 解析 WHERE 条件 → 生成 ExecutionPlan → 置入缓存
    │     └─ 命中 → 深拷贝 PlanCondition[]
    │
    ▼
选择最优索引（等值优先）
    │
    ├─ 无索引 → 返回 no_index
    │
    ▼
B+树索引查询（O(logN)）
    │
    ├─ 单条件 → 直接返回
    ├─ 范围组合 → 合并为一次 getMoreAndLessThen
    └─ 多条件 → 索引查出候选行 + 内存逐行过滤
    │
    ▼
返回 QueryResult
```

### 4.2 写入流程（复制模式）

```
应用写入请求
    │
    ▼
ReplicationManager.insert/update/delete()
    │
    ├─ standalone → 直接调 Table
    ├─ master → Table + OpLog.append() + SyncClient.broadcast()
    └─ slave → forwardOrBuffer()
           ├─ Master 可达 → HTTP POST 转发
           └─ Master 不可达 → pendingQueue 缓冲
```

---

## 5. 安全设计

| 措施 | 说明 |
|------|------|
| SQL 注入防护 | `/cache/load` 只接受表名参数（正则 `[a-zA-Z0-9_]+`），SQL 从配置文件读取 |
| 网络白名单 | 生产推荐防火墙对负载均衡 IP 放行，零性能损耗 |
| 表名校验 | 所有涉及表名的接口均做正则校验 |
| 行冻结 | 加载完成后行数据冻结，应用层不能误修改 |
| 连接池 | 轻量级连接池，默认 5 连接，超时 10 秒 |

---

## 6. 客户端路由（多组扩展）

当单机内存不足以覆盖所有表时，按表分组到多个 CacheSQL 集群，各集群独立主从复制。

### 6.1 架构

```
App → CacheSQLClient（按表名路由）
           │
      ┌────┼────┐
      ▼    ▼    ▼
    group1  group2  group3
    (社保)  (医保)  (就业)
    KCA2    YB01    JY01
    KCA3    YB02    JY02
```

### 6.2 配置

```properties
cachesql.group.insurance.master=http://192.168.1.10:8080
cachesql.group.insurance.slaves=http://192.168.1.11:8080,http://192.168.1.12:8080
cachesql.group.insurance.tables=KCA2,KCA3

cachesql.group.medical.master=http://192.168.1.20:8080
cachesql.group.medical.slaves=http://192.168.1.21:8080
cachesql.group.medical.tables=YB01,YB02
```

### 6.3 使用

```java
CacheSQLClient client = new CacheSQLClient("cachesql.properties");
List<Map<String, Object>> rows = client.get("KCA2", "AAC001", "12345");
client.insert("KCA2", "AAC001", "99999", data);
```

读：自动路由 + 随机选 master/slave。写：自动路由到 master。

### 6.4 设计原理

**不分片**——每组内表是全量，单机装不下才按表分组。组间不做分片合并，不引入分布式复杂度。

**无转发**——请求直连目标组，应用端路由不做中间跳，零额外延迟。

**分区表适配**——对关系库分区表，用 SQL 参数限定加载子集：

```properties
cache.table.CALL_RECORDS.sql=SELECT * FROM CALL_RECORDS WHERE MONTH = ?
cache.table.CALL_RECORDS.params=202604
```

客户端只需修改参数值即可切换分区，不需要理解集群拓扑。

---

## 7. 设计原则

### 不做的事

| 不做 | 理由 |
|------|------|
| **JOIN** | JOIN 的 O(n²) 复杂度是数学下限，Oracle 都翻车，缓存层不该碰 |
| **分片** | 单机能装下就不分，分片引入的分布式复杂度远超收益 |
| **持久化** | 数据源在关系库，缓存只做加速层，丢了 reload 即可 |
| **服务端路由转发** | 多一跳延迟翻倍，不如应用端直连 |
| **全功能 SQL** | 只支持 SELECT + 索引可用条件，缓存层不是查询引擎 |

### 做的事

| 做 | 理由 |
|----|------|
| 单表点查询 | B+Tree 稳定几十微秒，读多写少场景最优解 |
| 主从复制 | OpLog 环形缓冲 + 异步广播，够用且简单 |
| 读无锁 | 写方法 synchronized，读全无锁，320 万 QPS 吞吐 |
| 配置驱动 | SQL、索引、参数全部来自 config.properties，零硬编码 |
| 客户端路由 | 3 行 HashMap 搞定多组扩展，不依赖中间件 |

---

## 8. 多数据库支持

| 数据库 | 驱动 | JDBC URL 示例 |
|--------|------|---------------|
| Oracle | ojdbc6 (必选) | `jdbc:oracle:thin:@host:1521:orcl` |
| MySQL | mysql-connector-java 8.0.33 (optional) | `jdbc:mysql://host:3306/db` |
| PostgreSQL | postgresql 42.6.0 (optional) | `jdbc:postgresql://host:5432/db` |

切换数据库只需修改 `config.properties` 中的 `db.driver`、`db.url`、`db.username`、`db.password`。

---

## 9. 限制说明

| 项目 | 限制 |
|------|------|
| SQL 类型 | 仅支持 SELECT |
| JOIN | 不支持（多表用数据库视图） |
| LIKE | 仅支持 `'prefix%'` 前缀匹配 |
| 结果行数 | 默认上限 10000（可通过修改源码 `MAX_RESULT_ROWS` 调整） |
| 单表行数 | 受 JVM 堆内存限制（建议 `-Xmx` 的 70% 以内） |
| 索引列类型 | 自动识别为 Long/Double/String |
