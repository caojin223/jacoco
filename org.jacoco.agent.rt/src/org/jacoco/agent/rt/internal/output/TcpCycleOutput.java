/*******************************************************************************
 * Copyright (c) 2009, 2022 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Marc R. Hoffmann - initial API and implementation
 *
 *******************************************************************************/
package org.jacoco.agent.rt.internal.output;

import org.jacoco.agent.rt.internal.IExceptionLogger;
import org.jacoco.core.runtime.AgentOptions;
import org.jacoco.core.runtime.RuntimeData;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;

/**
 * @author caoji 通过TCP连接到服务端，定时或按需上报覆盖率数据
 */
public class TcpCycleOutput implements IAgentOutput {

	private final IExceptionLogger logger;

	private TcpConnection connection;

	private Thread worker;

	private Thread heartbeat;

	private final String printHeader = "-----jacoco----------> ";

	private volatile boolean running = true;

	/**
	 * 标记最后发送时间，用于发送心跳
	 */
	private volatile long last = System.currentTimeMillis();

	/**
	 * 心跳间隔
	 */
	private int interval;

	private String server, module, commit, classDir, gitUrl;

	/**
	 * New controller instance.
	 *
	 * @param logger
	 *            logger to use in case of exceptions is spawned threads
	 */
	public TcpCycleOutput(final IExceptionLogger logger) {
		this.logger = logger;
	}

	@Override
	public void startup(final AgentOptions options, final RuntimeData data)
			throws IOException {
		checkArgs(options);
		worker = new Thread(new Runnable() {
			@Override
			public void run() {
				int i = 0;
				while (running) {
					Socket socket = null;
					try {
						socket = createSocket(options);
						System.out.printf(printHeader + "%s已连接服务端%s%n",
								socket.getLocalSocketAddress(),
								socket.getRemoteSocketAddress());
						heartbeat = new Thread(
								new Heartbeat(socket, TcpCycleOutput.this));
						heartbeat.setName(getClass().getName() + "heartbeat");
						heartbeat.setDaemon(true);
						socket.setKeepAlive(true);
						connection = new TcpConnection(socket, data);
						connection.init();
						// 用于通知服务端初始化项目信息，如拉取代码等
						connection.sendProjectInfo(server, module, commit,
								classDir, gitUrl);
						setLastSend();
						heartbeat.start();
						i = 0;
						connection.run();
					} catch (final IOException e) {
						if (e instanceof SocketException) {
							if ("Connection reset".equals(e.getMessage())) {
								System.err.println(
										printHeader + "Socket disconnect");
							}
						}
					} finally {
						if (socket != null) {
							try {
								socket.close();
								heartbeat.interrupt();
							} catch (IOException e) {
								e.printStackTrace();
							}
						}
					}
					// System.err.println(printHeader + "与服务端连接断开");
					sleeper(10 * 1000
							* (i < 30 ? 1 : (i < 100 ? 2 * i / 30 : 18)));
					if (running) {
						System.err.printf(printHeader + "尝试重连中......，第%d次\n",
								++i);
					}
				}
			}
		});
		worker.setName(getClass().getName());
		worker.setDaemon(true);
		worker.start();
	}

	public void shutdown() throws Exception {
		running = false;
		connection.close();
		worker.join();
	}

	public void writeExecutionData(final boolean reset) throws IOException {
		connection.writeExecutionData(reset);
		setLastSend();
	}

	/**
	 * Open a socket based on the given configuration.
	 *
	 * @param options
	 *            address and port configuration
	 * @return opened socket
	 * @throws IOException
	 */
	protected Socket createSocket(final AgentOptions options)
			throws IOException {
		return new Socket(options.getAddress(), options.getPort());
	}

	public static void sleeper(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			e.printStackTrace();
			Thread.currentThread().interrupt();
		}
	}

	private void setLastSend() {
		last = System.currentTimeMillis();
	}

	private void checkArgs(final AgentOptions options) {
		this.server = options.getServerName();
		if (this.server == null || this.server.isEmpty()) {
			throw new IllegalArgumentException("server arg is required.");
		}
		this.classDir = options.getClassDumpDir();
		if (this.classDir == null || this.classDir.isEmpty()) {
			options.setClassDumpDir("classes");
			this.classDir = options.getClassDumpDir();
		}
		this.gitUrl = options.getGitUrl();
		if (this.gitUrl == null || this.gitUrl.isEmpty()) {
			throw new IllegalArgumentException("giturl is required.");
		}
		this.module = options.getModuleName();
		this.commit = options.getCommit();
		this.interval = options.getHeartbeat();
	}

	static class Heartbeat implements Runnable {
		private final Socket socket;
		private final TcpCycleOutput cycle;

		Heartbeat(Socket socket, TcpCycleOutput cycle) {
			this.socket = socket;
			this.cycle = cycle;
		}

		@Override
		public void run() {
			int interval = cycle.interval * 1000;
			try {
				System.out.printf(cycle.printHeader + "心跳间隔%ss.%n",
						cycle.interval);
				while (!socket.isClosed()) {
					long cost = System.currentTimeMillis() - cycle.last;
					if (cost >= interval) {
						System.out.println("♥");
						cycle.connection.sendHeartbeat();
						cycle.setLastSend();
						synchronized (this) {
							wait(interval);
						}
					} else {
						long timeout = interval - cost;
						synchronized (this) {
							wait(timeout);
						}
					}
				}
			} catch (Exception e) {
				if (!(e instanceof InterruptedException)) {
					e.printStackTrace();
				}
			}
		}
	}

}
