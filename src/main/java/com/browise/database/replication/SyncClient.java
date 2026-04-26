package com.browise.database.replication;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.browise.core.util.DBUtil;
import com.browise.database.replication.OpLog.OpEntry;

/* Master端同步客户端：向所有Slave广播写操作 + 定时心跳检测 */
/* Master sync client: broadcasts write ops to all slaves + periodic heartbeat detection */
/* 广播通过单线程Executor顺序发送，保证op顺序一致。心跳独立线程检测Slave存活 */
/* Broadcast via single-thread executor for ordered delivery. Heartbeat on separate thread for liveness detection */
public class SyncClient {

	private final String[] slaveUrls;
	/* 每个Slave的最后确认seq，用于断线重连后的增量补发 */
	/* Per-slave last acked seq, used for incremental catch-up after reconnect */
	private final long[] slaveLastSeq;
	private final boolean[] slaveAlive;
	private final Object aliveLock = new Object();
	private final ExecutorService broadcastExecutor;
	private ScheduledExecutorService heartbeatScheduler;
	private final OpLog opLog;

	/**
	 * 创建同步客户端，从配置读取Slave地址列表并初始化状态数组
	 * Create sync client, read slave URL list from config and initialize state arrays
	 * @param opLog 操作日志引用，用于断线重连后增量补发 / op log reference for incremental catch-up
	 */
	public SyncClient(OpLog opLog) {
		this.opLog = opLog;
		String slavesStr = DBUtil.getConfig("server.slaves", "");
		if (slavesStr.isEmpty()) {
			slaveUrls = new String[0];
		} else {
			slaveUrls = slavesStr.split(",");
			for (int i = 0; i < slaveUrls.length; i++) {
				slaveUrls[i] = slaveUrls[i].trim();
			}
		}
		slaveLastSeq = new long[slaveUrls.length];
		slaveAlive = new boolean[slaveUrls.length];
		for (int i = 0; i < slaveUrls.length; i++) {
			synchronized (aliveLock) { slaveAlive[i] = true; }
		}
		broadcastExecutor = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "sync-broadcast");
			t.setDaemon(true);
			return t;
		});
	}

	/**
	 * 启动同步客户端：获取各Slave状态，补发缺失操作，启动心跳检测线程
	 * Start sync client: fetch each slave's status, replay missed ops, start heartbeat thread
	 */
	public void start() {
		if (slaveUrls.length == 0) return;

		for (int i = 0; i < slaveUrls.length; i++) {
			slaveLastSeq[i] = fetchSlaveStatus(slaveUrls[i]);
			System.out.println("[sync] slave " + slaveUrls[i] + " lastSeq=" + slaveLastSeq[i]);
		}

		catchupAllSlaves();

		int hbInterval = DBUtil.getConfigInt("server.heartbeat.interval", 5000);
		heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "sync-heartbeat");
			t.setDaemon(true);
			return t;
		});
		heartbeatScheduler.scheduleAtFixedRate(this::heartbeat, hbInterval, hbInterval, TimeUnit.MILLISECONDS);

		System.out.println("[sync] SyncClient started, slaves=" + slaveUrls.length);
	}

	/**
	 * 停止同步客户端：关闭广播执行器和心跳调度器
	 * Stop sync client: shutdown broadcast executor and heartbeat scheduler
	 */
	public void stop() {
		broadcastExecutor.shutdown();
		if (heartbeatScheduler != null) heartbeatScheduler.shutdown();
	}

	/* 异步广播写操作到所有Slave。单线程Executor保证顺序 */
	/* Async broadcast write op to all slaves. Single-thread executor ensures ordering */
	public void broadcast(String op, String table, String indexColumn, Object keyValue,
			HashMap<String, Object> data, long seq) {
		if (slaveUrls.length == 0) return;
		broadcastExecutor.submit(() -> {
			for (int i = 0; i < slaveUrls.length; i++) {
				synchronized (aliveLock) { if (!slaveAlive[i]) continue; }
				try {
					sendOp(slaveUrls[i], op, table, indexColumn, keyValue, data, seq);
					slaveLastSeq[i] = seq;
				} catch (Exception e) {
					synchronized (aliveLock) { slaveAlive[i] = false; }
					System.out.println("[sync] slave " + slaveUrls[i] + " broadcast failed: " + e.getMessage());
				}
			}
		});
	}

	/* 心跳检测：对每个Slave发送GET /sync/heartbeat，连续失败标记为不可用 */
	/* Heartbeat: sends GET /sync/heartbeat to each slave, marks unavailable on consecutive failures */
	private void heartbeat() {
		for (int i = 0; i < slaveUrls.length; i++) {
			try {
				HttpURLConnection conn = (HttpURLConnection) new URL(slaveUrls[i] + "/sync/heartbeat")
						.openConnection();
				conn.setRequestMethod("GET");
				conn.setConnectTimeout(3000);
				conn.setReadTimeout(3000);
				int code = conn.getResponseCode();
				conn.disconnect();
				if (code == 200) {
					boolean wasDead;
					synchronized (aliveLock) {
						wasDead = !slaveAlive[i];
						slaveAlive[i] = true;
					}
					if (wasDead) {
						System.out.println("[sync] slave " + slaveUrls[i] + " recovered, catching up...");
						catchupSlave(i);
					}
				} else {
					synchronized (aliveLock) { slaveAlive[i] = false; }
				}
			} catch (Exception e) {
				synchronized (aliveLock) { slaveAlive[i] = false; }
			}
		}
	}

	/* Slave恢复后增量补发：从OpLog获取该slave缺失的op逐条发送 */
	/* Incremental catch-up after slave recovery: fetch missed ops from OpLog and send one by one */
	private void catchupSlave(int idx) {
		List<OpEntry> missed = opLog.getSince(slaveLastSeq[idx]);
		for (OpEntry entry : missed) {
			try {
				sendOp(slaveUrls[idx], entry.op, entry.table, entry.indexColumn, entry.keyValue, entry.data,
						entry.seq);
				slaveLastSeq[idx] = entry.seq;
			} catch (Exception e) {
				synchronized (aliveLock) { slaveAlive[idx] = false; }
				System.out.println("[sync] catchup failed for " + slaveUrls[idx] + ": " + e.getMessage());
				return;
			}
		}
		System.out.println("[sync] slave " + slaveUrls[idx] + " catchup done, replayed=" + missed.size()
				+ " lastSeq=" + slaveLastSeq[idx]);
	}

	/**
	 * 对所有Slave执行增量补发，遍历调用catchupSlave
	 * Incremental catch-up for all slaves, iterates calling catchupSlave
	 */
	private void catchupAllSlaves() {
		for (int i = 0; i < slaveUrls.length; i++) {
			catchupSlave(i);
		}
	}

	/**
	 * 向单个Slave发送一个写操作（HTTP POST表单）
	 * Send a single write operation to a slave (HTTP POST form-encoded)
	 */
	private void sendOp(String slaveUrl, String op, String table, String indexColumn, Object keyValue,
			HashMap<String, Object> data, long seq) throws Exception {
		StringBuilder body = new StringBuilder();
		body.append("seq=").append(seq);
		body.append("&op=").append(op);
		body.append("&table=").append(table);
		if (indexColumn != null) body.append("&indexColumn=").append(indexColumn);
		if (keyValue != null) body.append("&keyValue=").append(keyValue);
		if (data != null) {
			for (HashMap.Entry<String, Object> entry : data.entrySet()) {
				if (entry.getValue() != null) {
					body.append("&").append(entry.getKey()).append("=").append(entry.getValue());
				}
			}
		}

		HttpURLConnection conn = (HttpURLConnection) new URL(slaveUrl + "/sync/op").openConnection();
		conn.setRequestMethod("POST");
		conn.setDoOutput(true);
		conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
		conn.setConnectTimeout(3000);
		conn.setReadTimeout(5000);
		OutputStream os = conn.getOutputStream();
		os.write(body.toString().getBytes("UTF-8"));
		os.close();
		conn.getResponseCode();
		conn.disconnect();
	}

	/**
	 * 从Slave获取其最新已确认序列号(lastSeq)
	 * Fetch the latest acknowledged sequence number (lastSeq) from a slave
	 */
	private long fetchSlaveStatus(String slaveUrl) {
		try {
			HttpURLConnection conn = (HttpURLConnection) new URL(slaveUrl + "/sync/status").openConnection();
			conn.setRequestMethod("GET");
			conn.setConnectTimeout(3000);
			conn.setReadTimeout(3000);
			if (conn.getResponseCode() == 200) {
				java.io.InputStream is = conn.getInputStream();
				byte[] buf = new byte[256];
				int len = is.read(buf);
				is.close();
				conn.disconnect();
				String resp = new String(buf, 0, len, "UTF-8");
				int idx = resp.indexOf("lastSeq");
				if (idx >= 0) {
					int colon = resp.indexOf(":", idx);
					int end = resp.indexOf("}", colon);
					if (colon > 0 && end > colon) {
						return Long.parseLong(resp.substring(colon + 1, end).trim());
					}
				}
			}
			conn.disconnect();
		} catch (Exception e) {
			System.out.println("[sync] fetch status from " + slaveUrl + " failed: " + e.getMessage());
		}
		return 0;
	}

	public int getSlaveCount() {
		return slaveUrls.length;
	}

	/**
	 * 获取当前存活Slave的数量
	 * Get the count of currently alive slaves
	 */
	public int getAliveCount() {
		int alive = 0;
		synchronized (aliveLock) {
			for (boolean a : slaveAlive) if (a) alive++;
		}
		return alive;
	}
}
