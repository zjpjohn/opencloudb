/*
 * Copyright 2012-2015 org.opencloudb.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/**
 * (created at 2012-4-19)
 */
package org.opencloudb.mysql.nio.handler;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.log4j.Logger;
import org.opencloudb.MycatConfig;
import org.opencloudb.MycatServer;
import org.opencloudb.config.ErrorCode;
import org.opencloudb.mysql.MySQLDataNode;
import org.opencloudb.mysql.nio.MySQLConnection;
import org.opencloudb.net.mysql.ErrorPacket;
import org.opencloudb.net.mysql.OkPacket;
import org.opencloudb.route.RouteResultsetNode;
import org.opencloudb.server.NonBlockingSession;
import org.opencloudb.server.ServerConnection;
import org.opencloudb.util.StringUtil;

/**
 * @author mycat
 */
public class SingleNodeHandler implements ResponseHandler, Terminatable {
	private static final Logger LOGGER = Logger
			.getLogger(SingleNodeHandler.class);

	private final RouteResultsetNode route;
	private final NonBlockingSession session;
	private byte packetId;
	private volatile ByteBuffer buffer;
	private ReentrantLock lock = new ReentrantLock();
	private boolean isRunning;
	private Runnable terminateCallBack;

	public SingleNodeHandler(RouteResultsetNode route,
			NonBlockingSession session) {
		if (route == null) {
			throw new IllegalArgumentException("routeNode is null!");
		}
		if (session == null) {
			throw new IllegalArgumentException("session is null!");
		}
		this.session = session;
		this.route = route;
	}

	@Override
	public void terminate(Runnable callback) {
		boolean zeroReached = false;
		lock.lock();
		try {
			if (isRunning) {
				terminateCallBack = callback;
			} else {
				zeroReached = true;
			}
		} finally {
			lock.unlock();
		}
		if (zeroReached) {
			callback.run();
		}
	}

	private void endRunning() {
		Runnable callback = null;
		lock.lock();
		try {
			if (isRunning) {
				isRunning = false;
				callback = terminateCallBack;
				terminateCallBack = null;
			}
		} finally {
			lock.unlock();
		}
		if (callback != null) {
			callback.run();
		}
	}

	private void recycleResources() {
		ByteBuffer buf;
		lock.lock();
		try {
			buf = buffer;
			if (buf != null) {
				buffer = null;
			}
		} finally {
			lock.unlock();
		}
		if (buf != null) {
			session.getSource().recycle(buf);
		}
	}

	public void execute() throws Exception {
		lock.lock();
		try {
			this.isRunning = true;
			this.packetId = 0;
			this.buffer = session.getSource().allocate();
		} finally {
			lock.unlock();
		}
		final MySQLConnection conn = session.getTarget(route);
		if (conn == null) {
			MycatConfig conf = MycatServer.getInstance().getConfig();
			MySQLDataNode dn = conf.getDataNodes().get(route.getName());
			dn.getConnection(this, null);
		} else {
			conn.setAttachment(route);
			session.getSource().getProcessor().getExecutor()
					.execute(new Runnable() {
						@Override
						public void run() {
							_execute(conn);
						}
					});
		}
	}

	@Override
	public void connectionAcquired(final MySQLConnection conn) {
		conn.setRunning(true);
		session.bindConnection(route, conn);
		session.getSource().getProcessor().getExecutor()
				.execute(new Runnable() {
					@Override
					public void run() {
						_execute(conn);
					}
				});
	}

	private void _execute(MySQLConnection conn) {
		if (session.closed()) {
			conn.setRunning(false);
			endRunning();
			session.clearConnections();
			return;
		}
		conn.setResponseHandler(this);
		try {
			conn.execute(route, session.getSource(), session.getSource()
					.isAutocommit());
		} catch (UnsupportedEncodingException e1) {
			executeException(conn);
			return;
		}
	}

	private void executeException(MySQLConnection c) {
		c.setRunning(false);
		endRunning();
		session.clearConnections();
		ErrorPacket err = new ErrorPacket();
		err.packetId = ++packetId;
		err.errno = ErrorCode.ER_YES;
		err.message = StringUtil.encode(
				"unknown backend charset: " + c.getCharset(), session
						.getSource().getCharset());
		ServerConnection source = session.getSource();
		source.write(err.write(buffer, source));
	}

	@Override
	public void connectionError(Throwable e, MySQLConnection conn) {
		System.out.println("connectionError:" + e.toString());
		if (!session.closeConnection(route)) {
			conn.close();
		}
		endRunning();
		ErrorPacket err = new ErrorPacket();
		err.packetId = ++packetId;
		err.errno = ErrorCode.ER_YES;
		err.message = StringUtil.encode(e.getMessage(), session.getSource()
				.getCharset());
		ServerConnection source = session.getSource();
		source.write(err.write(buffer, source));
	}

	@Override
	public void errorResponse(byte[] err, MySQLConnection conn) {
		// System.out.println("received errorResponse from conn:" + conn);

		conn.setRunning(false);
		removeSessionAttachement(conn, true);
		endRunning();
		session.getSource().write(err);
	}

	@Override
	public void okResponse(byte[] data, MySQLConnection conn) {
		// System.out.println("received okResponse from conn:" + conn);
		boolean executeResponse = false;
		try {
			executeResponse = conn.syncAndExcute();
		} catch (UnsupportedEncodingException e) {
			executeException(conn);
		}
		if (executeResponse) {
			conn.setRunning(false);
			ServerConnection source = session.getSource();
			if (source.isAutocommit()) {

				// System.out
				// .println("okResponse executeResponse  clear conentions");
				session.clearConnections();
			}
			endRunning();
			OkPacket ok = new OkPacket();
			ok.read(data);
			source.setLastInsertId(ok.insertId);
			buffer = source.writeToBuffer(data, buffer);
			source.write(buffer);
			recycleResources();
		}
	}

	/**
	 * by wuzh
	 * 
	 * @param conn
	 * @param backConErrFound
	 */
	private void removeSessionAttachement(MySQLConnection conn,
			boolean backConErrFound) {
		// System.out.println("removeSessionAttachement " + conn);
		ServerConnection source = session.getSource();
		if (source.isAutocommit()) {
			conn = session.removeTarget(route);
			if (conn != null) {
				if (backConErrFound || session.closed()) {
					conn.quit();
				} else {
					conn.release();
				}
			}
			session.clearConnections();
		}
	}

	@Override
	public void rowEofResponse(byte[] eof, MySQLConnection conn) {
		// System.out.println("received  rowEofResponse from conn:" + conn);
		ServerConnection source = session.getSource();
		conn.setRunning(false);
		conn.recordSql(source.getHost(), source.getSchema(),
				route.getStatement());

		removeSessionAttachement(conn, false);
		endRunning();
		lock.lock();
		try {
			eof[3] = ++packetId;
			buffer = source.writeToBuffer(eof, buffer);
			source.write(buffer);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void fieldEofResponse(byte[] header, List<byte[]> fields,
			byte[] eof, MySQLConnection conn) {
		// System.out.println("received  fieldEofResponse from conn:" + conn);
		lock.lock();
		try {
			header[3] = ++packetId;
			ServerConnection source = session.getSource();
			buffer = source.writeToBuffer(header, buffer);
			for (int i = 0, len = fields.size(); i < len; ++i) {
				byte[] field = fields.get(i);
				field[3] = ++packetId;
				buffer = source.writeToBuffer(field, buffer);
			}
			eof[3] = ++packetId;
			buffer = source.writeToBuffer(eof, buffer);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void rowResponse(byte[] row, MySQLConnection conn) {
		// System.out.println("received rowResponse from conn:" + conn);
		lock.lock();
		try {
			row[3] = ++packetId;
			buffer = session.getSource().writeToBuffer(row, buffer);
		} finally {
			lock.unlock();
		}
	}

}