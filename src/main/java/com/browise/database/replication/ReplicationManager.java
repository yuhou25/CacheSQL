package com.browise.database.replication;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.HashMap;

import com.browise.core.util.DBUtil;
import com.browise.database.table.Table;

/* 复制管理器：应用层写操作的统一入口。核心层(Table/BPTree/Node)零修改 */
/* Replication manager: unified entry point for application writes. Core layer (Table/BPTree/Node) untouched */
/* standalone: 透传Table。master: 本地执行+OpLog+广播。slave: 转发Master，失败时缓存在本地队列，恢复后重放 */
/* Standalone: pass-through. Master: local+log+broadcast. Slave: forward to master, buffer on failure, replay on recovery */
public class ReplicationManager {

	private static final String ROLE = DBUtil.getConfig("server.role", "standalone");
	private static final OpLog opLog = new OpLog(DBUtil.getConfigInt("server.oplog.capacity", 10000));
	private static SyncClient syncClient;
	private static SyncServer syncServer;
	private static final String masterUrl = DBUtil.getConfig("server.master.url", "").trim();

	/* Slave侧待转发队列：Master不可达时缓存写操作，恢复后按FIFO重放 */
	/* Slave-side pending queue: buffers writes when master unreachable, replays FIFO on recovery */
	private static final int pendingCapacity = DBUtil.getConfigInt("server.pending.capacity", 5000);
	private static final ArrayDeque<PendingOp> pendingQueue = new ArrayDeque<PendingOp>();
	private static Thread pendingFlushThread;

	/* Master可达状态：上次转发成功则为true，避免每次都探测 */
	/* Master reachability: true if last forward succeeded, avoids probing every time */
	private static volatile boolean masterReachable = true;

	public static final String ROLE_STANDALONE = "standalone";
	public static final String ROLE_MASTER = "master";
	public static final String ROLE_SLAVE = "slave";

	/**
	 * 初始化复制管理器，根据角色(standalone/master/slave)启动相应组件
	 * Initialize replication manager, start components based on role (standalone/master/slave)
	 */
	public static void init() throws Exception {
		if (ROLE_MASTER.equals(ROLE)) {
			syncClient = new SyncClient(opLog);
			syncClient.start();
		} else if (ROLE_SLAVE.equals(ROLE)) {
			int syncPort = DBUtil.getConfigInt("server.sync.port", 19091);
			syncServer = new SyncServer(syncPort);
			syncServer.start();
			if (masterUrl.isEmpty()) {
				System.err.println("[replication] WARNING: slave mode but server.master.url not configured");
			}
			startPendingFlushThread();
		}
		System.out.println("[replication] role=" + ROLE + (ROLE_SLAVE.equals(ROLE) ? " master=" + masterUrl : ""));
	}

	/**
	 * 关闭复制管理器，停止所有后台线程和服务
	 * Shutdown replication manager, stop all background threads and services
	 */
	public static void shutdown() {
		if (pendingFlushThread != null) pendingFlushThread.interrupt();
		if (syncClient != null) syncClient.stop();
		if (syncServer != null) syncServer.stop();
	}

	/* ========== 写操作入口 ========== */

	/**
	 * 插入数据：Slave转发到Master，Master本地执行并广播给所有Slave
	 * Insert data: slave forwards to master, master executes locally and broadcasts to all slaves
	 */
	public static void insert(Table table, String indexColumn, Object keyValue,
			HashMap<String, Object> newData) throws Exception {
		if (ROLE_SLAVE.equals(ROLE)) {
			forwardOrBuffer("insert", table.getName(), indexColumn, keyValue, newData);
			return;
		}
		table.insert(indexColumn, keyValue, newData);
		if (ROLE_MASTER.equals(ROLE) && syncClient != null) {
			long seq = opLog.append("insert", table.getName(), indexColumn, keyValue, newData);
			syncClient.broadcast("insert", table.getName(), indexColumn, keyValue, newData, seq);
		}
	}

	/**
	 * 更新数据：Slave转发到Master，Master本地执行并广播给所有Slave
	 * Update data: slave forwards to master, master executes locally and broadcasts to all slaves
	 */
	public static void update(Table table, String indexColumn, Object keyValue,
			HashMap<String, Object> newData) throws Exception {
		if (ROLE_SLAVE.equals(ROLE)) {
			forwardOrBuffer("update", table.getName(), indexColumn, keyValue, newData);
			return;
		}
		table.update(indexColumn, keyValue, newData);
		if (ROLE_MASTER.equals(ROLE) && syncClient != null) {
			long seq = opLog.append("update", table.getName(), indexColumn, keyValue, newData);
			syncClient.broadcast("update", table.getName(), indexColumn, keyValue, newData, seq);
		}
	}

	/**
	 * 删除数据：Slave转发到Master，Master本地执行并广播给所有Slave
	 * Delete data: slave forwards to master, master executes locally and broadcasts to all slaves
	 */
	public static void delete(Table table, String indexColumn, Object keyValue) throws Exception {
		if (ROLE_SLAVE.equals(ROLE)) {
			forwardOrBuffer("delete", table.getName(), indexColumn, keyValue, null);
			return;
		}
		table.delete(indexColumn, keyValue);
		if (ROLE_MASTER.equals(ROLE) && syncClient != null) {
			long seq = opLog.append("delete", table.getName(), indexColumn, keyValue, null);
			syncClient.broadcast("delete", table.getName(), indexColumn, keyValue, null, seq);
		}
	}

	/* ========== Slave转发 + 故障缓冲 ========== */

	/* 先尝试直接转发；若Master不可达则写入pendingQueue，由后台线程恢复后重放 */
	/* Try direct forward first; if master unreachable, buffer to pendingQueue for background replay */
	private static void forwardOrBuffer(String op, String table, String indexColumn, Object keyValue,
			HashMap<String, Object> data) throws Exception {
		if (masterUrl.isEmpty()) {
			throw new RuntimeException("server.master.url not configured for slave node");
		}
		String keyStr = keyValue != null ? String.valueOf(keyValue) : null;
		if (masterReachable && pendingQueue.isEmpty()) {
			try {
				doForward(op, table, indexColumn, keyStr, data);
				return;
			} catch (Exception e) {
				masterReachable = false;
				System.err.println("[replication] master unreachable, buffering: " + e.getMessage());
			}
		}
		synchronized (pendingQueue) {
			if (pendingQueue.size() >= pendingCapacity) {
				pendingQueue.pollFirst();
			}
			pendingQueue.addLast(new PendingOp(op, table, indexColumn, keyStr, data));
		}
	}

	/* HTTP POST转发到Master。SyncServer收到广播后直接调table.insert()，不走ReplicationManager，不循环 */
	/* HTTP POST forward to master. SyncServer replays via table.insert() directly, no ReplicationManager, no loop */
	private static void doForward(String op, String table, String indexColumn, String keyValue,
			HashMap<String, Object> data) throws Exception {
		StringBuilder body = new StringBuilder();
		body.append("table=").append(table);
		if (indexColumn != null) body.append("&column=").append(indexColumn);
		if (keyValue != null) body.append("&value=").append(keyValue);
		if (data != null) {
			for (HashMap.Entry<String, Object> entry : data.entrySet()) {
				if (entry.getValue() != null) {
					body.append("&").append(entry.getKey()).append("=").append(entry.getValue());
				}
			}
		}
		HttpURLConnection conn = (HttpURLConnection) new URL(masterUrl + "/cache/" + op).openConnection();
		conn.setRequestMethod("POST");
		conn.setDoOutput(true);
		conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
		conn.setConnectTimeout(3000);
		conn.setReadTimeout(10000);
		OutputStream os = conn.getOutputStream();
		os.write(body.toString().getBytes("UTF-8"));
		os.close();
		int code = conn.getResponseCode();
		conn.disconnect();
		if (code != 200) {
			throw new RuntimeException("Master returned HTTP " + code);
		}
	}

	/* 后台线程：定期尝试清空pendingQueue。先发队首，成功则逐个弹出；失败则等下一轮 */
	/* Background thread: periodically drains pendingQueue. Sends head first; on success pops one by one; on failure waits */
	private static void startPendingFlushThread() {
		final int interval = DBUtil.getConfigInt("server.pending.flush.interval", 2000);
		pendingFlushThread = new Thread(new Runnable() {
			public void run() {
				System.out.println("[replication] pending flush thread started, interval=" + interval + "ms");
				while (!Thread.currentThread().isInterrupted()) {
					try {
						Thread.sleep(interval);
					} catch (InterruptedException e) {
						break;
					}
					if (pendingQueue.isEmpty()) continue;
					try {
						flushPending();
					} catch (Exception e) {
						/* flush内部已处理，此处忽略 */
					}
				}
			}
		}, "pending-flush");
		pendingFlushThread.setDaemon(true);
		pendingFlushThread.start();
	}

	/* 按FIFO顺序重放pendingQueue中的操作。逐个转发，失败即停（保持顺序） */
	/* Replay pendingQueue FIFO. Forward one by one, stop on failure (preserves order) */
	private static void flushPending() {
		while (true) {
			PendingOp op;
			synchronized (pendingQueue) {
				op = pendingQueue.peekFirst();
			}
			if (op == null) break;
			try {
				doForward(op.op, op.table, op.indexColumn, op.keyValue, op.data);
				synchronized (pendingQueue) {
					pendingQueue.pollFirst();
				}
			} catch (Exception e) {
				masterReachable = false;
				return;
			}
		}
		masterReachable = true;
	}

	/* ========== 查询方法 ========== */

	public static OpLog getOpLog() {
		return opLog;
	}

	public static String getRole() {
		return ROLE;
	}

	public static String getMasterUrl() {
		return masterUrl;
	}

	/**
	 * 获取Slave侧待转发队列当前大小
	 * Get current size of slave-side pending forward queue
	 */
	public static int getPendingCount() {
		synchronized (pendingQueue) {
			return pendingQueue.size();
		}
	}

	public static boolean isMasterReachable() {
		return masterReachable;
	}

	/* 测试用：手动触发flushPending / For testing: manually trigger flushPending */
	public static void flushPendingPublic() {
		flushPending();
	}

	/* 测试用：清空pendingQueue / For testing: clear pendingQueue */
	public static void clearPending() {
		synchronized (pendingQueue) {
			pendingQueue.clear();
		}
		masterReachable = true;
	}

	public static boolean isMaster() {
		return ROLE_MASTER.equals(ROLE);
	}

	public static boolean isSlave() {
		return ROLE_SLAVE.equals(ROLE);
	}

	public static boolean isStandalone() {
		return ROLE_STANDALONE.equals(ROLE);
	}

	/* 待转发操作：与OpLog.OpEntry结构类似，但无seq（尚未到达Master，未分配序列号） */
	/* Pending operation: similar to OpLog.OpEntry but no seq (hasn't reached master yet, no sequence number assigned) */
	private static class PendingOp {
		final String op;
		final String table;
		final String indexColumn;
		final String keyValue;
		final HashMap<String, Object> data;

		PendingOp(String op, String table, String indexColumn, String keyValue,
				HashMap<String, Object> data) {
			this.op = op;
			this.table = table;
			this.indexColumn = indexColumn;
			this.keyValue = keyValue;
			this.data = data != null ? new HashMap<String, Object>(data) : null;
		}
	}
}
