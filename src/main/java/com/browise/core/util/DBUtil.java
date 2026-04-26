package com.browise.core.util;

import java.io.File;
import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Properties;

import com.browise.core.exception.utilException;

/* 数据库工具：配置加载、轻量级连接池(BlockingDeque实现)、JDBC驱动管理 */
/* DB utility: config loading, lightweight connection pool (BlockingDeque), JDBC driver management */
public class DBUtil {
    private static final Properties config = new Properties();
    private static volatile boolean driverLoaded = false;

    /* 轻量级连接池：ArrayDeque + synchronized + wait/notify。默认5连接，超时10秒 */
    /* Lightweight connection pool: ArrayDeque + synchronized + wait/notify. Default 5 connections, 10s timeout */
    private static final int POOL_MAX = getConfigInt("db.pool.maxActive", 5);
    private static final long POOL_TIMEOUT = getConfigLong("db.pool.timeout", 10000);
    private static final Deque<Connection> pool = new ArrayDeque<Connection>();
    private static int poolActive = 0;
    private static final Object poolLock = new Object();

    static {
        loadConfig();
    }

    /**
     * 从 config.properties 和系统属性加载配置，系统属性优先级最高
     * Load config from config.properties and system properties, system properties take precedence
     */
    private static void loadConfig() {
        File file = new File("config.properties");
        if (file.exists()) {
            try {
                Properties props = new Properties();
                FileInputStream fis = new FileInputStream(file);
                props.load(fis);
                fis.close();
                config.putAll(props);
                System.out.println("Loaded config from " + file.getAbsolutePath());
            } catch (Exception e) {
                System.out.println("Failed to load config.properties: " + e.getMessage());
            }
        }

        String override = System.getProperty("config");
        if (override != null) {
            try {
                Properties props = new Properties();
                FileInputStream fis = new FileInputStream(override);
                props.load(fis);
                fis.close();
                config.putAll(props);
                System.out.println("Loaded config from " + override);
            } catch (Exception e) {
                System.out.println("Failed to load " + override + ": " + e.getMessage());
            }
        }

        System.getProperties().forEach((k, v) -> {
            String key = String.valueOf(k);
            if (key.startsWith("db.") || key.startsWith("server.") || key.startsWith("cache.")) {
                config.setProperty(key, String.valueOf(v));
            }
        });
    }

    /**
     * 获取配置项值，不存在时返回默认值
     * Get config value, return default if key not found
     */
    public static String getConfig(String key, String defaultValue) {
        return config.getProperty(key, defaultValue);
    }

    /**
     * 获取所有配置项 key 集合
     * Get all config key names
     */
    public static java.util.Set<String> getConfigKeys() {
        return config.stringPropertyNames();
    }

    /**
     * 获取整数配置项，解析失败返回默认值
     * Get int config value, return default on parse failure
     */
    public static int getConfigInt(String key, int defaultValue) {
        String val = config.getProperty(key);
        if (val == null) return defaultValue;
        try { return Integer.parseInt(val.trim()); } catch (NumberFormatException e) { return defaultValue; }
    }

    /**
     * 获取长整数配置项，解析失败返回默认值
     * Get long config value, return default on parse failure
     */
    public static long getConfigLong(String key, long defaultValue) {
        String val = config.getProperty(key);
        if (val == null) return defaultValue;
        try { return Long.parseLong(val.trim()); } catch (NumberFormatException e) { return defaultValue; }
    }

    /**
     * 确保 JDBC 驱动已加载，双重检查锁保证线程安全
     * Ensure JDBC driver is loaded, double-checked locking for thread safety
     */
    private static void ensureDriver() throws utilException {
        if (!driverLoaded) {
            synchronized (DBUtil.class) {
                if (!driverLoaded) {
                    String driver = config.getProperty("db.driver");
                    if (driver == null || driver.isEmpty()) {
                        throw new utilException("db.driver not configured. Check config.properties.", -9004);
                    }
                    String url = config.getProperty("db.url");
                    if (url == null || url.isEmpty()) {
                        throw new utilException("db.url not configured. Check config.properties.", -9004);
                    }
                    try {
                        Class.forName(driver);
                        driverLoaded = true;
                    } catch (ClassNotFoundException e) {
                        throw new utilException("Driver not found: " + driver, -9001);
                    }
                }
            }
        }
    }

    /**
     * 使用配置的 url/username/password 创建新数据库连接
     * Create a new DB connection using configured url/username/password
     */
    private static Connection createConnection() throws utilException {
        ensureDriver();
        try {
            return DriverManager.getConnection(
                config.getProperty("db.url"),
                config.getProperty("db.username"),
                config.getProperty("db.password")
            );
        } catch (Exception e) {
            throw new utilException("Failed to create connection: " + e.getMessage(), -9000);
        }
    }

    /* 获取连接：优先复用池中空闲连接，不够则新建(不超过POOL_MAX)，池满则等待超时 */
    /* Get connection: reuse pooled idle connection first, create new if under POOL_MAX, wait if pool exhausted */
    public static Connection getConnection() throws utilException {
        synchronized (poolLock) {
            long deadline = System.currentTimeMillis() + POOL_TIMEOUT;
            while (true) {
                Connection conn = pool.pollFirst();
                if (conn != null) {
                    try {
                        if (!conn.isClosed()) {
                            poolActive++;
                            return conn;
                        }
                    } catch (Exception e) {
                        continue;
                    }
                }
                if (poolActive + pool.size() < POOL_MAX) {
                    Connection newConn = createConnection();
                    poolActive++;
                    return newConn;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new utilException("Connection pool exhausted (max=" + POOL_MAX + ")", -9002);
                }
                try {
                    poolLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new utilException("Interrupted waiting for connection", -9003);
                }
            }
        }
    }

    /* 归还连接到池。如果连接已关闭则丢弃，唤醒等待的获取线程 */
    /* Return connection to pool. Discard if closed, wake up waiting acquire threads */
    public static void returnConnection(Connection conn) {
        if (conn == null) return;
        synchronized (poolLock) {
            try {
                if (!conn.isClosed()) {
                    pool.addLast(conn);
                }
            } catch (Exception e) {
                // connection broken, discard
            }
            poolActive--;
            poolLock.notifyAll();
        }
    }

    /**
     * 关闭连接池中所有空闲连接
     * Close all idle connections in the pool
     */
    public static void closePool() {
        synchronized (poolLock) {
            Connection conn;
            while ((conn = pool.pollFirst()) != null) {
                try { conn.close(); } catch (Exception ignored) {}
            }
        }
    }

    public static int poolIdle() {
        synchronized (poolLock) { return pool.size(); }
    }

    public static int poolActive() {
        synchronized (poolLock) { return poolActive; }
    }
}
