# CacheSQL 部署手册

## 1. 环境要求

### 1.1 硬件

| 项目 | 最低要求 | 推荐 |
|------|---------|------|
| CPU | 2 核 | 4 核及以上 |
| 内存 | 4 GB（含 JVM） | 按数据量估算（见 1.3） |
| 磁盘 | 500 MB（程序+日志） | 无特殊要求 |
| 网络 | 千兆网卡 | 万兆（高吞吐场景） |

### 1.2 软件

| 项目 | 版本 |
|------|------|
| JDK | 1.8+（推荐 Oracle JDK 8u191+ 或 OpenJDK 8+） |
| Maven | 3.6+（仅编译时需要） |
| Oracle 客户端 | 可选（如有 Oracle 数据库需安装对应版本 OCI） |
| 操作系统 | Linux / Windows / macOS |

### 1.3 内存估算

单表内存估算公式：

```
单行开销 ≈ 168 + 16 + 8 × 列数 + 40 × 列数 + 索引数 × 64 (bytes)
B+树开销 ≈ 索引数 × 行数 × 20 (bytes)
总内存 ≈ 行数 × 单行开销 + B+树开销
JVM -Xmx 建议 ≥ 总内存 × 1.5（含 15% 安全余量）
```

示例：100 万行、20 列、3 个索引的表：
- 单行 ≈ 168 + 16 + 160 + 800 + 192 = 1336 bytes
- 总内存 ≈ 100 万 × 1336 + 300 万 × 20 ≈ 1.4 GB
- JVM -Xmx 建议 ≥ 2.1 GB

---

## 2. 编译打包

### 2.1 获取源码

```
git clone <repository-url>
cd CacheSQL
```

### 2.2 编译

```bash
mvn clean package -DskipTests
```

编译产物：`target/CacheSQL-1.0-SNAPSHOT.jar`

如需排除可选依赖（MySQL/PostgreSQL），使用：

```bash
mvn clean package -DskipTests -Dmysql.skip=true -Dpostgresql.skip=true
```

### 2.3 Oracle 驱动说明

`ojdbc6-11.2.0.jar` 需手动安装到本地 Maven 仓库：

```bash
mvn install:install-file -Dfile=path/to/ojdbc6.jar -DgroupId=com.oracle -DartifactId=ojdbc6 -Dversion=11.2.0 -Dpackaging=jar
```

或直接将 ojdbc6.jar 放入 classpath。

---

## 3. 配置文件

### 3.1 配置文件路径

配置文件 `config.properties` 默认放在**工作目录下**（即运行 `java -jar` 的目录）。  
可通过系统属性 `-Dconfig=/path/to/config.properties` 指定其他路径。

### 3.2 完整配置项说明

```properties
# ==== 运行模式 ====
# server.mode: embed（内嵌，无 HTTP）/ all（HTTP 服务）
server.mode=all
# HTTP 服务端口（仅 all 模式生效）
server.port=8080

# ==== HTTP 引擎 ====
# server.http.engine: jdk（BIO，零外部依赖）/ undertow（NIO，高性能）
server.http.engine=jdk

# ---- BIO 引擎参数（server.http.engine=jdk）----
# 线程池大小，0=自动（CPU核数×2）
server.http.threads=0
# TCP 积压队列长度，0=操作系统默认
server.http.backlog=0

# ---- NIO 引擎参数（server.http.engine=undertow）----
# IO 线程数（NIO Selector 线程），0=自动（max(2, CPU核数)）
server.http.ioThreads=0
# Worker 线程数（请求处理线程），0=自动（ioThreads×8）
server.http.workerThreads=0
# 每连接读写缓冲区大小（字节），默认 16384 = 16KB
server.http.bufferSize=16384
# HTTP 请求体最大长度（字节），默认 1048576 = 1MB，超限返回 413
server.http.maxEntitySize=1048576

# ==== 数据库连接 ====
# 选择一种数据库配置，取消对应行注释

# --- Oracle（取消注释并填写实际连接信息）---
#db.driver=oracle.jdbc.driver.OracleDriver
#db.url=jdbc:oracle:thin:@your_oracle_host:1521:orcl
#db.username=your_user
#db.password=your_pass

# --- MySQL（取消注释）---
#db.driver=com.mysql.cj.jdbc.Driver
#db.url=jdbc:mysql://localhost:3306/mydb?useSSL=false&characterEncoding=utf8
#db.username=root
#db.password=123456

# --- PostgreSQL（取消注释）---
#db.driver=org.postgresql.Driver
#db.url=jdbc:postgresql://localhost:5432/mydb
#db.username=postgres
#db.password=postgres

# ==== 连接池 ====
db.pool.maxActive=5
db.pool.timeout=10000

# ==== 定时刷新 ====
# 自动刷新间隔（毫秒），0=不刷新
cache.refreshInterval=0

# ==== 复制模式 ====
# 角色: standalone / master / slave
server.role=standalone
# Slave→Master 转发地址（仅 slave 需要）
# server.master.url=http://192.168.1.10:8080
# Slave 端同步监听端口
server.sync.port=19091
# Master 的 Slave 地址列表（逗号分隔，仅 master 需要）
# server.slaves=http://192.168.1.11:19091,http://192.168.1.12:19091
# OpLog 环形缓冲区容量
server.oplog.capacity=10000
# 心跳间隔（毫秒）
server.heartbeat.interval=5000
# Slave 侧待转发队列容量（Master 不可达时缓存写操作，默认 5000，满时丢弃最旧）
server.pending.capacity=5000
# Slave 侧待转发队列重试间隔（毫秒，默认 2000）
server.pending.flush.interval=2000

# ==== 表定义 ====
# 语法: cache.table.<表名>.sql=SELECT ...
#        cache.table.<表名>.indexes=列名1,列名2,列名1+列名2
#        cache.table.<表名>.params=参数1,参数2

# 示例：单表全量加载
# cache.table.KCA2.sql=SELECT * FROM KCA2
# cache.table.KCA2.indexes=AAC001,AAC002,AAC003

# 示例：带参数的增量加载
# cache.table.KCA2.sql=SELECT * FROM KCA2 WHERE AAC001 > ?
# cache.table.KCA2.params=9999900000
# cache.table.KCA2.indexes=AAB004,AAC002,BAC001

# 注意：取消注释的表定义会在服务启动时自动加载（无需手动调用 /cache/load）。
# 如果所有表定义保持注释状态，系统以空缓存启动，后续可通过 POST /cache/load 手动加载。
```

### 3.3 配置优先级

1. 系统属性 `-Dkey=value`（最高）
2. 额外配置文件 `-Dconfig=/path/to/file`
3. 工作目录下 `config.properties`
4. 代码中的默认值

### 3.4 HTTP 引擎参数详解

CacheSQL 支持两种 HTTP 引擎，通过 `server.http.engine` 切换，无需修改业务代码。

#### 3.4.1 引擎对比

| 特性 | BIO (JDK) | NIO (Undertow) |
|------|-----------|----------------|
| IO 模型 | 阻塞 IO，每连接一线程 | 非阻塞 IO，IO 多路复用 |
| 外部依赖 | 零依赖（JDK 内置） | undertow-core 2.2（+1.2MB） |
| 8 线程读取 QPS | ~16,000 | ~29,000（+83%） |
| 8 线程写入 QPS | ~17,000 | ~29,000（+69%） |
| 适用场景 | 开发测试、低并发、零依赖要求 | 生产环境、高并发、性能优先 |
| 启动日志标识 | `[http] BIO engine started` | `[http] NIO engine started` |

#### 3.4.2 BIO 引擎参数

```
server.http.engine=jdk
```

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `server.http.threads` | `CPU×2` | BIO 线程池大小。每个并发请求占用一个线程，线程池满时请求排队 |
| `server.http.backlog` | `0` (OS默认) | TCP 连接积压队列长度。高并发短连接场景建议设为 128~512 |

**BIO 线程数调优：**

| 场景 | 推荐值 | 说明 |
|------|--------|------|
| 低并发（< 100 QPS） | `CPU×2` | 默认值即可 |
| 中并发（100~1000 QPS） | `CPU×4` | 避免线程池满导致排队 |
| 高并发（> 1000 QPS） | 建议切 NIO | BIO 线程开销大，QPS 天花板低 |

#### 3.4.3 NIO 引擎参数

```
server.http.engine=undertow
```

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `server.http.ioThreads` | `max(2, CPU)` | NIO Selector 线程数。负责接受连接和 IO 读写，**通常不需要修改** |
| `server.http.workerThreads` | `ioThreads×8` | Worker 线程数。负责执行业务逻辑（SQL 解析、B+ 树查询、JSON 序列化） |
| `server.http.bufferSize` | `16384` (16KB) | 每连接读写缓冲区大小。增大可减少系统调用次数，但增加内存占用 |
| `server.http.maxEntitySize` | `1048576` (1MB) | HTTP 请求体最大长度。超限返回 413 Payload Too Large |

**NIO 线程模型：**

```
┌─────────────────────────────────────────────┐
│                NIO Engine                    │
│                                              │
│  ┌──────────────────────────────┐            │
│  │  IO Threads (ioThreads)      │            │
│  │  Selector: accept + read/write│            │
│  │  不执行业务逻辑              │            │
│  └──────────────┬───────────────┘            │
│                 │ 分发请求                    │
│  ┌──────────────▼───────────────┐            │
│  │  Worker Threads (workerThreads)│           │
│  │  执行: SQL解析→B+树查询→JSON │            │
│  │  读操作无锁，写操作同步保护   │            │
│  └──────────────────────────────┘            │
└─────────────────────────────────────────────┘
```

**NIO 参数调优：**

| 场景 | ioThreads | workerThreads | bufferSize | 说明 |
|------|-----------|---------------|------------|------|
| 一般生产 | `CPU` | `CPU×8` | `16384` | 默认值即可 |
| 读多写少 | `CPU` | `CPU×4` | `16384` | 读操作无锁，线程少也能高吞吐 |
| 高并发写 | `CPU` | `CPU×16` | `16384` | 写操作有 synchronized 锁，多线程排队 |
| 大结果集 | `CPU` | `CPU×8` | `32768` | 增大 buffer 减少 IO 次数 |
| 内存受限 | `CPU/2` | `CPU×4` | `8192` | 减少 buffer 内存占用 |

#### 3.4.4 引擎切换示例

**从 BIO 切换到 NIO，只需改一行配置：**

```properties
# 修改前
server.http.engine=jdk

# 修改后
server.http.engine=undertow
```

重启后日志从：
```
[http] BIO engine started: port=8080 threads=32 backlog=0
```
变为：
```
[http] NIO engine started: port=8080 ioThreads=16 workerThreads=128 bufferSize=16384 maxEntitySize=1048576
```

**运行时覆盖（无需改配置文件）：**

```bash
java -Dserver.http.engine=undertow -Dserver.http.workerThreads=64 -jar CacheSQL-1.0-SNAPSHOT.jar
```

---

## 4. 启动

### 4.1 基本启动

```bash
java -jar CacheSQL-1.0-SNAPSHOT.jar
```

> 如果 `config.properties` 中配置了表定义（`cache.table.<name>.sql` 取消注释），启动时会自动加载所有已配置的表，无需手动调用 `/cache/load`。

### 4.2 指定端口

```bash
java -jar CacheSQL-1.0-SNAPSHOT.jar 9090
```

### 4.3 指定堆内存

```bash
java -Xmx4g -Xms2g -jar CacheSQL-1.0-SNAPSHOT.jar
```

### 4.4 指定配置文件路径

```bash
java -Dconfig=/etc/cachesql/config.properties -jar CacheSQL-1.0-SNAPSHOT.jar
```

### 4.5 内嵌模式（不开 HTTP）

```properties
server.mode=embed
```

```bash
java -jar CacheSQL-1.0-SNAPSHOT.jar
```

---

## 5. 复制模式部署

### 5.1 架构图

```
                  ┌──────────────┐
                  │    Master     │   ← 写操作入口
                  │  192.168.1.10 │
                  │  HTTP:8080    │
                  │  Sync:19091   │  ← 接收 Slave 心跳/状态
                  └──────┬───────┘
                         │ 广播写操作 + 心跳
            ┌────────────┼────────────┐
            ▼            ▼            ▼
     ┌──────────┐ ┌──────────┐ ┌──────────┐
     │ Slave 1  │ │ Slave 2  │ │ Slave N  │  ← 读操作入口
     │ .1.11    │ │ .1.12    │ │ ...      │
     │ Sync:19091│ │ Sync:19091│ │          │
     └──────────┘ └──────────┘ └──────────┘
```

### 5.2 Master 配置

```properties
server.role=master
server.port=8080
server.slaves=http://192.168.1.11:19091,http://192.168.1.12:19091
server.oplog.capacity=10000
server.heartbeat.interval=5000
```

### 5.3 Slave 配置

```properties
server.role=slave
server.port=8080
server.master.url=http://192.168.1.10:8080
server.sync.port=19091
```

### 5.4 启动顺序

1. 先启动 Master
2. 再启动 Slave（Slave 启动时自动从数据库加载全量数据，Master 的 SyncClient 通过 OpLog 补发增量写入）
3. 应用**读操作和写操作均可发往任意节点**（Slave 收到写请求自动转发 Master）
4. 写操作路径：Slave → HTTP 转发 Master → Master 执行 + 广播所有 Slave → Slave 回放
5. Master 不可达时，Slave 的写操作缓存在 `pendingQueue`，恢复后自动重发

---

## 6. 负载均衡部署

### 6.1 推荐架构

```
                   ┌──────────┐
                   │   LB     │  ← 防火墙只对 LB IP 放行
                   │ (Nginx/  │
                   │  F5/HA)  │
                   └────┬─────┘
              ┌──────────┼──────────┐
              ▼          ▼          ▼
         ┌────────┐ ┌────────┐ ┌────────┐
         │ Master │ │ Slave  │ │ Slave  │
         └────────┘ └────────┘ └────────┘
              │          │          │
              └──────────┴──────────┘
                         │
                   ┌─────▼─────┐
                   │   Oracle  │
                   └───────────┘
```

### 6.2 安全建议

- 防火墙对 LB 的 IP 白名单放行 8080/19091 端口
- LB 负责 TLS 终结（如有 HTTPS 需求）
- CacheSQL 节点之间不额外加密（内网安全）

---

## 7. 启动验证

```bash
# 1. 检查进程
ps aux | grep CacheSQL

# 2. 加载测试表（如已配置 cache.table.KCA2.sql 并取消注释，此步可跳过）
curl -X POST http://localhost:8080/cache/load -d "table=KCA2"

# 3. 查询测试
curl "http://localhost:8080/cache/get?table=KCA2&column=AAC001&value=1"

# 4. 健康检查
curl http://localhost:8080/cache/health

# 5. 查看统计
curl http://localhost:8080/cache/metrics
```

---

## 8. 停止

### 8.1 优雅停止

```bash
kill <pid>
```

程序通过 ShutdownHook 自动执行：
1. 停止定时刷新
2. 关闭 ReplicationManager（停止广播/心跳/接收线程）
3. 停止 HTTP 服务器

### 8.2 强制停止

```bash
kill -9 <pid>
```

---

## 9. 常见问题

### Q: 启动报 "Driver not found: oracle.jdbc.driver.OracleDriver"

A: `ojdbc6.jar` 不在 classpath 中。可通过 Maven 安装到本地仓库后重新打包，或将 jar 文件放在 classpath 下。

### Q: 启动报 "db.driver not configured"

A: 确保 `config.properties` 在工作目录下，或使用 `-Dconfig=` 指定路径。

### Q: 加载大表时 OOM

A: 增加 JVM 堆内存：`-Xmx8g -Xms4g`。加载前系统会打印内存估算日志，据此调整。

### Q: Slave 节点无法连接 Master

A: 检查：
1. Master 的 `server.port` 是否可访问
2. Slave 的 `server.master.url` 配置是否正确
3. 防火墙是否放行对应端口
4. Slave 的写操作会自动缓存在 `pendingQueue`，Master 恢复后自动重发，不会丢失

### Q: Slave 写操作丢失

A: 不会丢失。Slave 在 Master 不可达时将写操作缓存在内存队列（`pendingQueue`，默认容量 5000），后台线程每 2 秒尝试重发。如果队列满则丢弃最旧的操作（FIFO 溢出），可通过 `server.pending.capacity` 调整。

### Q: 如何选择 BIO 还是 NIO 引擎？

A:
- **开发/测试/低并发**：BIO（`server.http.engine=jdk`），零依赖，简单可靠
- **生产/高并发**：NIO（`server.http.engine=undertow`），8 线程读取 QPS 提升 60%
- 如果 JAR 包大小不敏感（+1.2MB），直接用 NIO 即可

### Q: NIO 的 ioThreads 和 workerThreads 分别调什么？

A:
- `ioThreads`：NIO Selector 线程，负责网络 IO（accept/read/write），**通常不改**，默认 = CPU 核数
- `workerThreads`：业务处理线程，执行 SQL 解析、B+ 树查询、JSON 序列化，**这是主要调优参数**
- 如果 CPU 利用率低但 QPS 上不去 → 增大 `workerThreads`
- 如果 CPU 已满 → 增大 `workerThreads` 无效，瓶颈在业务逻辑（锁竞争）

### Q: HTTP QPS 远低于内嵌模式 QPS，正常吗？

A: 正常。内嵌模式（Java API 直接调用 Table）峰值 320 万 QPS，HTTP 模式（NIO 8 线程）约 3.0 万 QPS。差距来自：
1. TCP 连接建立/关闭开销
2. HTTP 协议解析
3. JSON 序列化/反序列化
4. 短连接场景（每次 `conn.disconnect()`）

如需更高 QPS，建议使用内嵌模式或 HTTP keep-alive 长连接。
