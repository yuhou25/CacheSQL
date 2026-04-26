# CacheSQL 项目上下文存档

## 项目定位
CacheSQL — 内存缓存数据库中间件，面向政务 OLTP 读多写少场景，Oracle 读缓存加速层。

## 代码位置
`D:\work\CacheSQL\`（已从 myMemoryDB 改名）

## 技术栈
- Java 1.8 + Maven
- jsqlparser 4.9 + JDBC（Oracle/MySQL/PostgreSQL）
- JDK 内置 HttpServer（零外部 HTTP 依赖）
- Undertow 2.2.28（可选 NIO 引擎）
- B+ 树索引引擎、软删除、TTL/LRU 淘汰
- 主从复制（standalone/master/slave）+ pendingQueue 故障缓冲

## 配置
`D:\work\CacheSQL\config.properties`：
```
server.mode=all (或 embed)
server.http.engine=jdk (或 undertow)
server.role=standalone (或 master/slave)
```
Oracle: 192.168.70.26:1521/orcl, user=core/pass=core

## 项目状态
- 53 项单元测试 全绿，JDK 8/11/17/21 四版本通过
- 6 项集成测试（主从复制完整生命周期）通过
- 30 项引擎对比测试（BIO vs NIO）通过
- 审计整改完成：13项修复（S级1/M级4/P级5/L级3）+ 2项剩余已知 LOW
- 可部署

## 性能基准
| 场景 | QPS |
|------|-----|
| 内嵌 get() 16线程 | 154万 |
| 内嵌 SQL 16线程 | 92万 |
| HTTP NIO 读 8线程 | 2.1万 |
| HTTP NIO 写 8线程 | 2.5万 |
| 内嵌 insert 单线程 | 12.8万 |
| 内嵌 update 单线程 | 51万 |

## 文档
- `CacheSQL_功能说明书.md`
- `CacheSQL_部署手册.md`
- `CacheSQL_操作手册.md`
- `CacheSQL_测试报告.md`（功能+集成+性能，401行）
- `CacheSQL_性能测试.md`（多 JDK 读对比，142行）
- `CacheSQL_代码审计报告.md`（原始审计，289行）
- `CacheSQL_代码审计报告_整改后.md`（整改后，150行）

## 已完成的工作
1. 假数据生成：100 万行 KCA2（gen_kca2_1m.py）
2. 注释审计、并发修复（C-1 PlanCache深拷贝）
3. 复制模块实现（ReplicationManager + SyncClient/Server + OpLog）
4. 双 HTTP 引擎（JDK + Undertow 可配置切换）
5. JSON 解析重写（逗号切割 → 有限状态机）
6. SyncClient 竞态修复（aliveLock + synchronized）
7. 代码审计 + 整改（15项→3项剩余 LOW）
8. 三份文档 + 两份测试报告 + 两份审计报告
9. H5 智能导办方案（独立文档，不由此项目实现）

## 已知剩余问题
- R-1: planCache.clear() 全清非 LRU（低频无影响）
- R-2: slaveLastSeq 未加同步保护（同子网内网无影响）

## 项目上下文非 git 仓库
