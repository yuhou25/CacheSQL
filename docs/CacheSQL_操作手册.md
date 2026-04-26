# CacheSQL 操作手册

## 1. 快速开始

### 1.1 配置表定义

编辑 `config.properties`，添加要缓存的表：

```properties
cache.table.KCA2.sql=SELECT * FROM KCA2
cache.table.KCA2.indexes=AAC001,AAC002,AAC003
```

> **注意**：取消注释的表定义会在服务启动时自动加载到缓存，无需手动调用 `/cache/load`。如需手动控制加载时机，保持表定义注释状态，启动后通过 API 加载。

支持多个表：

```properties
cache.table.KCA2.sql=SELECT * FROM KCA2
cache.table.KCA2.indexes=AAC001

cache.table.AAC003.sql=SELECT * FROM AAC003 WHERE AAC001 > 9999900000
cache.table.AAC003.params=9999900000
cache.table.AAC003.indexes=AAC001,AAC002
```

### 1.2 启动服务

```bash
java -Xmx4g -jar CacheSQL-1.0-SNAPSHOT.jar
```

如果 `config.properties` 中配置了表定义（未注释），启动时会自动加载所有表，日志示例：

```
[cache] table 'KCA2' loaded, rows=1000000
[cache] auto-loaded 1 table(s) from config
```

### 1.3 加载表到缓存

> 如果表已在 `config.properties` 中配置并取消注释，启动时已自动加载，此步可跳过。

```bash
curl -X POST http://localhost:8080/cache/load -d "table=KCA2"
```

---

## 2. 查询操作

### 2.1 SQL 查询

```bash
# 等值查询
curl "http://localhost:8080/cache/query?sql=SELECT%20*%20FROM%20KCA2%20WHERE%20AAC001=12345"

# 范围查询
curl "http://localhost:8080/cache/query?sql=SELECT%20*%20FROM%20KCA2%20WHERE%20AAC001%3E10000%20AND%20AAC001%3C20000"
```

响应格式：

```json
{
  "code": 0,
  "message": "ok",
  "data": [
    {"AAC001": 12345, "AAC002": "张三", "AAC003": "男"},
    ...
  ]
}
```

字段说明：

| code | 说明 |
|------|------|
| 0 | 成功 |
| 1 | 无对应的索引 |
| HTTP 400 | 参数缺失 |
| HTTP 404 | 表未找到 |
| HTTP 500 | 服务端异常 |

### 2.2 等值查询

```bash
curl "http://localhost:8080/cache/get?table=KCA2&column=AAC001&value=12345"
```

参数：
- `table`：表名
- `column`：索引列名
- `value`：查询值

### 2.3 小于查询

```bash
curl "http://localhost:8080/cache/less?table=KCA2&column=AAC001&value=10000"
```

返回所有 `AAC001 < 10000` 的行。

### 2.4 大于查询

```bash
curl "http://localhost:8080/cache/more?table=KCA2&column=AAC001&value=9999900000"
```

返回所有 `AAC001 > 9999900000` 的行。

### 2.5 范围查询

```bash
curl "http://localhost:8080/cache/range?table=KCA2&column=AAC001&from=10000&to=20000"
```

返回 `10000 ≤ AAC001 ≤ 20000` 的所有行（闭区间）。

### 2.6 联合索引查询

```bash
curl "http://localhost:8080/cache/composite?table=KCA2&index=AAB004+AAC002&values=张三,男"
```

参数：
- `index`：联合索引名（在 `config.properties` 中配置为 `AAB004+AAC002`）
- `values`：逗号分隔的各列值

### 2.7 LIKE 前缀查询

通过 SQL 接口使用：

```bash
curl "http://localhost:8080/cache/query?sql=SELECT%20*%20FROM%20KCA2%20WHERE%20AAC003%20LIKE%20'张%'"
```

注意：仅支持 `'prefix%'` 格式，不支持 `'%...'` 或 `'%...%'`。

---

## 3. 写入操作

写入操作的行为取决于 `server.role` 配置：

| 角色 | insert/update/delete 行为 |
|------|--------------------------|
| standalone | 直接修改本地 Table |
| master | 修改本地 Table + 记录 OpLog + 广播到所有 Slave |
| slave | 转发请求到 Master（Master 执行后广播回所有 Slave） |

**insert 幂等性**：insert 采用 upsert 语义——如果主键已存在且行活跃则覆盖更新，如果行已删除则复用槽位，不会产生重复行。Slave 在 Master 恢复后重放 pendingQueue 时天然不会导致数据重复。

**Slave 容错**：Master 不可达时，Slave 的写操作缓存在内存队列（`pendingQueue`，默认 5000 条），后台线程自动重试。队列满时丢弃最旧操作（FIFO 溢出）。

### 3.1 插入

```bash
curl -X POST http://localhost:8080/cache/insert \
  -d "table=KCA2&column=AAC001&value=9999999999&AAC002=张三&AAC003=男"
```

- `table`：表名
- `column`：主键索引列名
- `value`：主键值（HTTP 参数以 String 传输，系统自动按索引列类型转换）

### 3.2 更新

```bash
curl -X POST http://localhost:8080/cache/update \
  -d "table=KCA2&column=AAC001&value=12345&AAC002=李四"
```

根据主键 `AAC001=12345` 找到行，更新 `AAC002` 为 `李四`。参数类型自动适配索引列。

### 3.3 删除

```bash
curl -X POST http://localhost:8080/cache/delete \
  -d "table=KCA2&column=AAC001&value=12345"
```

软删除：仅标记 `isDelete=true`，行仍在内存中（可通过 `compact` 回收）。

---

## 4. 运维操作

### 4.1 查看表列表

```bash
curl http://localhost:8080/cache/list
```

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": [
    {"name": "KCA2", "sql": "SELECT * FROM KCA2"},
    {"name": "AAC003", "sql": "SELECT * FROM AAC003 WHERE AAC001 > ?"}
  ]
}
```

### 4.2 查看表统计信息

```bash
curl "http://localhost:8080/cache/stats?table=KCA2"
```

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "table": "KCA2",
    "totalRows": 1001425,
    "activeRows": 1000000,
    "ttl": 0,
    "maxSize": 0,
    "indexes": ["AAC001", "AAC002", "AAC003"]
  }
}
```

### 4.3 查看系统指标

```bash
curl http://localhost:8080/cache/metrics
```

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "tables": 2,
    "memory": {
      "used": 2147483648,
      "total": 4294967296,
      "max": 4294967296,
      "free": 2147483648
    },
    "threads": 12,
    "tables_detail": [
      {"name": "KCA2", "rows": 1000000, "capacity": 1001425, "indexes": 3},
      {"name": "AAC003", "rows": 500000, "capacity": 520000, "indexes": 2}
    ]
  }
}
```

### 4.4 健康检查

```bash
curl http://localhost:8080/cache/health
```

响应：

```json
{
  "code": 0,
  "message": "UP",
  "data": {
    "status": "UP",
    "tables": 2,
    "memory": {"used": 2048, "max": 4096},
    "pool": {"active": 1, "idle": 4}
  }
}
```

判断标准：
- 内存使用率 < 90% → `UP`
- 内存使用率 ≥ 90% → `WARN`（HTTP 503）

### 4.5 设置 TTL

```bash
curl -X POST http://localhost:8080/cache/ttl -d "table=KCA2&ms=3600000"
```

设置表 `KCA2` 的 TTL 为 1 小时（3600000 毫秒）。超过此时间未访问的行会在下次 `clean` 时被清除。

### 4.6 设置最大行数

```bash
curl -X POST http://localhost:8080/cache/maxSize -d "table=KCA2&size=1000000"
```

设置表最大行数为 100 万。超限时按 LRU 策略淘汰。

### 4.7 清理过期行

```bash
curl -X POST http://localhost:8080/cache/clean -d "table=KCA2"
```

清除表中所有超过 TTL 的行。

### 4.8 压缩已删除行

```bash
curl -X POST http://localhost:8080/cache/compact -d "table=KCA2"
```

物理删除所有标记为 `isDelete=true` 的行，回收内存空间。压缩后行索引（rowIdx）会变化，会自动重建所有 B+树索引。

### 4.9 清空表

```bash
curl -X POST http://localhost:8080/cache/truncate -d "table=KCA2"
```

清除表中所有数据和索引。

### 4.10 手动刷新

```bash
curl -X POST http://localhost:8080/cache/refresh
```

从数据库重新加载所有已配置的表。刷新期间查询不中断（原子替换表引用）。

---

## 5. 复制监控

### 5.1 查看 Master 状态

从 Master 节点的 `/cache/metrics` 中查看；在代码层面可通过 `ReplicationManager` 的以下方法：

| 方法 | 说明 |
|------|------|
| `getRole()` | 当前角色 |
| `getPendingCount()` | Slave 侧待转发队列大小 |
| `isMasterReachable()` | Master 是否可达 |
| `getOpLog().getLatestSeq()` | 最新 OpLog 序列号 |
| `getOpLog().size()` | OpLog 当前条目数 |

### 5.2 查看 Slave 状态

SyncServer 提供端点：

- `GET /sync/status` → `{"lastSeq": 12345}`（已回放到的最新序列号）
- `GET /sync/heartbeat` → `{"status": "UP"}`

Master 通过心跳检测 Slave 存活，断开后每分钟重试并自动增量补发。

### 5.3 Slave 故障恢复流程

1. Master 心跳检测发现 Slave 不可用 → 标记 `slaveAlive=false`
2. Slave 恢复 → 心跳成功 → Master 检测到存活
3. Master 查询 OpLog 获取 `lastSeq+1` 到 `latestSeq` 之间的所有操作
4. 逐条补发给 Slave（保持顺序）
5. Slave 回放完成后恢复正常服务

---

## 6. 性能基准

以下为 100 万行 KCA2 表（3 个 B+树索引）的测试数据：

| 操作 | 延迟 |
|------|------|
| 等值查询（单行） | < 1 μs |
| 范围查询（1000 行） | < 100 μs |
| 全表扫描（100 万行） | ~ 50 ms |
| 数据加载（100 万行） | 取决于 JDBC 速度 |
| 插入一行 | ~ 10 μs |
| 更新一行 | ~ 10 μs |
| 删除一行 | ~ 10 μs |

---

## 7. 日志

所有日志通过 `System.out.println` 输出到标准输出/标准错误。

关键日志事件：

| 日志内容 | 说明 |
|---------|------|
| `Loaded config from ...` | 配置文件加载 |
| `[cache] table '...' loaded, rows=...` | 启动自动加载表 |
| `[cache] auto-loaded N table(s) from config` | 自动加载汇总 |
| `[cache] failed to load table '...': ...` | 自动加载失败（不阻塞启动） |
| `[memory] table=... estimatedRows=...` | 内存估算 |
| `[FATAL] Insufficient memory for table ...` | 内存不足拒绝加载 |
| `[refresh] auto refresh started, interval=...ms` | 定时刷新启动 |
| `[refresh] KCA2 reloaded, rows=1000000` | 刷新完成 |
| `[replication] role=master` | 复制角色 |
| `[sync] SyncClient started, slaves=2` | Master 广播启动 |
| `[sync] SyncServer started on port 19091` | Slave 接收端启动 |
| `[replication] master unreachable, buffering: ...` | Slave 侧 Master 不可达 |
| `[sync] slave ... recovered, catching up...` | Slave 恢复，增量补发 |
| `[compact] table=KCA2 rows=1000000` | 压缩完成 |

---

## 8. 故障排查

### 8.1 查询返回空结果

可能原因：
1. **表未加载** → 先 `POST /cache/load`
2. **查询列未建索引** → 响应 `code: 1, message: "no index for this query"`
3. **值不匹配** → 确认查询值与索引列类型一致（如 String 类型查询加引号）

### 8.2 查询返回结果与数据库不一致

可能原因：
1. **缓存未刷新** → 执行 `POST /cache/refresh` 手动刷新
2. **定时刷新未开启** → 检查 `cache.refreshInterval` 是否大于 0

### 8.3 写入操作无响应

可能原因（slave 模式下）：
1. **Master 不可达** → 检查网络和 Master 端口
2. `pendingQueue` 已满 → 最早的操作被丢弃（队列 FIFO 溢出）
3. **`server.master.url` 未配置** → 运行时会抛异常

### 8.4 JVM OOM

排查步骤：
1. 检查 `cache/metrics` 查看内存使用
2. 减少 `-Xmx` 或减小 `MAX_RESULT_ROWS`
3. 启用 TTL/`maxSize` 限制表容量
4. 定期执行 `compact` 回收已删除行内存

### 8.5 Slave 数据不同步

排查步骤：
1. 在 Slave 端 `GET /sync/status` 查看 `lastSeq`
2. 在 Master 端 `ReplicationManager.getOpLog().getLatestSeq()` 对比序列号
3. 如果差距过大且 OpLog 已覆盖，需在 Slave 重新 `POST /cache/load`

---

## 9. 内嵌模式（Java API）

当 `server.mode=embed` 时，CacheSQL 以纯 Java API 方式运行，无 HTTP 开销。

### 9.1 初始化

```java
import com.browise.database.database;
import com.browise.database.table.Table;

// 自动加载 config.properties 中所有已配置的表
database.loadAllFromConfig();

// 或按需加载单表
Table table = database.loadFromConfig("KCA2");
```

### 9.2 查询

```java
import com.browise.database.table.Row;
import java.util.List;

// 等值查询
List<Row> rows = (List<Row>) table.get("AAC001", "12345");

// 范围查询
Object result = table.getMoreAndLessThen("AAC001", 10000L, 20000L);

// SQL 查询
import com.browise.database.SqlQueryEngine;
QueryResult qr = SqlQueryEngine.query("SELECT * FROM KCA2 WHERE AAC001 = 12345");
```

### 9.3 写入（通过 ReplicationManager）

```java
import com.browise.database.replication.ReplicationManager;
import java.util.HashMap;

// 插入（upsert 语义，主键重复则覆盖）
HashMap<String, Object> data = new HashMap<>();
data.put("AAC001", "9999999999");
data.put("AAC002", "张三");
data.put("AAC003", "男");
ReplicationManager.insert(table, "AAC001", "9999999999", data);

// 更新
HashMap<String, Object> updateData = new HashMap<>();
updateData.put("AAC002", "李四");
ReplicationManager.update(table, "AAC001", "12345", updateData);

// 删除（软删除）
ReplicationManager.delete(table, "AAC001", "12345");
```

写入自动路由：standalone 直接落本地，master 本地 + 广播，slave 转发 master。

### 9.4 运维操作

```java
// 压缩已删除行
table.compact();

// 清空表
table.truncate();

// 设置 TTL（1 小时）
table.setTTL(3600000L);

// 设置最大行数
table.setMaxSize(1000000);

// 清理过期行
int cleaned = table.cleanExpired();
```
