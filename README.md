# CacheSQL

**In-memory database cache middleware** — SQL-powered in-memory cache with B+Tree indexing, HTTP API, and master-slave replication.

Load relational database tables into JVM heap, serve point queries at microsecond latency, offload read pressure from Oracle/MySQL/PostgreSQL.

## Quick Start

```bash
# Build
mvn clean package -DskipTests

# Start
java -Xmx4g -jar target/CacheSQL-1.0-SNAPSHOT.jar
```

Tables configured in `config.properties` load automatically on startup.

```bash
# Query via HTTP
curl "http://localhost:8080/cache/get?table=MY_TABLE&column=ID&value=12345"

# SQL query
curl "http://localhost:8080/cache/query?sql=SELECT%20*%20FROM%20MY_TABLE%20WHERE%20ID%3E1000"

# Insert
curl -X POST http://localhost:8080/cache/insert \
  -d "table=MY_TABLE&column=ID&value=99999&NAME=hello"
```

## Features

- **SQL-driven** — Tables loaded via JDBC with configurable SQL + bind parameters
- **B+Tree indexing** — Single-column, composite, range, prefix LIKE; order-256 nodes
- **Dual HTTP engine** — JDK built-in (zero dependency) or Undertow NIO (higher throughput)
- **Master-slave replication** — OpLog ring buffer, async broadcast, heartbeat recovery, pending queue
- **Lock-free reads** — 3.2M QPS embedded read throughput (16-thread)
- **Soft delete** — O(1) mark-and-sweep; compact reclaims space with index rebuild
- **TTL / LRU eviction** — Per-table TTL and max-size eviction
- **JSON REST API** — Full CRUD, stats, health, metrics, composite indexes
- **JDK 8+ compatible** — Verified on JDK 8/11/17/21

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                   Application                        │
│  ┌────────────────────────────────────────────────┐  │
│  │  SqlQueryEngine     │  ReplicationManager      │  │
│  ├──────────────────────┼─────────────────────────┤  │
│  │  Table / rowSet / Row│  BPTree / Node          │  │
│  │  (in-memory table)   │  (B+Tree index engine)  │  │
│  ├──────────────────────┴─────────────────────────┤  │
│  │  DBUtil (connection pool + JDBC)                │  │
│  ├──────────────────────┬─────────────────────────┤  │
│  │  HttpCacheServer     │  SyncClient/SyncServer   │  │
│  │  (REST API / opt.)   │  (replication / opt.)    │  │
│  └──────────────────────┴─────────────────────────┘  │
│  config.properties                                    │
└──────────────────────────────────────────────────────┘
                       │
                       ▼
           ┌──────────────────────┐
           │  Backend DB           │
           │  (Oracle/MySQL/PG)    │
           └──────────────────────┘
```

## Performance (8-core, JDK 8)

| Mode | Operation | QPS | Latency |
|------|-----------|-----|---------|
| Embedded | `table.get()` 16-thread | **3,209,943** | 0.31 us |
| Embedded | SQL EQ 16-thread | **870,914** | 1.15 us |
| HTTP BIO | /get 8-thread | 16,115 | 62 us |
| HTTP NIO | SQL EQ 8-thread | **29,537** | 34 us |
| HTTP NIO | update 8-thread | **29,057** | 34 us |

## Configuration

```properties
# Database
db.driver=oracle.jdbc.driver.OracleDriver
db.url=jdbc:oracle:thin:@host:1521:orcl
db.username=user
db.password=pass

# Table definition
cache.table.EMPLOYEE.sql=SELECT * FROM EMPLOYEE
cache.table.EMPLOYEE.indexes=EMP_ID,DEPT_ID

# HTTP engine (jdk | undertow)
server.http.engine=undertow
```

## API Reference

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/cache/get` | GET | Index equality query |
| `/cache/query` | GET/POST | SQL query |
| `/cache/less` | GET | Less-than query |
| `/cache/more` | GET | Greater-than query |
| `/cache/range` | GET | Range query |
| `/cache/insert` | POST | Insert row (upsert) |
| `/cache/update` | POST | Update row |
| `/cache/delete` | POST | Delete row (soft delete) |
| `/cache/load` | POST | Load table from config |
| `/cache/list` | GET | List all tables |
| `/cache/stats` | GET | Table statistics |
| `/cache/metrics` | GET | Memory & thread metrics |
| `/cache/health` | GET | Health check |
| `/cache/refresh` | POST | Reload all tables from DB |

## Deployment

### Standalone
```properties
server.role=standalone
server.http.engine=undertow
```

### Master-Slave
```properties
# Master
server.role=master
server.slaves=http://slave1:19091,http://slave2:19091

# Slave
server.role=slave
server.master.url=http://master:8080
server.sync.port=19091
```

### JVM Tuning
```bash
java -Xms8g -Xmx8g \
     -XX:+UseG1GC \
     -XX:G1HeapRegionSize=16m \
     -XX:MaxTenuringThreshold=6 \
     -XX:InitiatingHeapOccupancyPercent=45 \
     -jar CacheSQL-1.0-SNAPSHOT.jar
```

## Client (multi-group routing)

For horizontal scaling across table groups, use the `CacheSQLClient`:

```properties
# cachesql.properties
cachesql.group.insurance.master=http://192.168.1.10:8080
cachesql.group.insurance.slaves=http://192.168.1.11:8080,http://192.168.1.12:8080
cachesql.group.insurance.tables=KCA2,KCA3

cachesql.group.medical.master=http://192.168.1.20:8080
cachesql.group.medical.slaves=http://192.168.1.21:8080
cachesql.group.medical.tables=YB01,YB02
```

```java
import com.browise.client.CacheSQLClient;

CacheSQLClient client = new CacheSQLClient("cachesql.properties");

// Read — auto-routes to table's group, random master/slave
List<Map<String, Object>> rows = client.get("KCA2", "AAC001", "12345");
rows = client.query("SELECT * FROM YB01 WHERE ID > 1000");

// Write — always routes to master
Map<String, Object> data = new HashMap<>();
data.put("AAC002", "张三");
client.insert("KCA2", "AAC001", "99999", data);
client.update("KCA2", "AAC001", "99999", data);
client.delete("KCA2", "AAC001", "99999");
```

## Requirements

- **JDK 8+** (verified on 8/11/17/21)
- **Maven 3.6+** (build only)
- **Oracle/MySQL/PostgreSQL JDBC driver**

## License

Apache 2.0
