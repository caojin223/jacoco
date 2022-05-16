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
import org.jacoco.core.runtime.ExtraInfo;
import org.jacoco.core.runtime.RuntimeData;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.Socket;

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

	/** 标记最后发送时间，用于发送心跳 */
	private volatile long last = System.currentTimeMillis();

	/** 心跳间隔 */
	private int interval = 60 * 1000;

	private String server, module, commit, classDir;

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
					setHeartbeatThread();
					Socket socket = null;
					try {
						socket = createSocket(options);
						connection = new TcpConnection(socket, data);
						data.setExtraInfo(new ExtraInfo(server, module, commit)
								.toString());
						connection.init();
						connection.sendServerName(server, module, commit,
								classDir);
						heartbeat.start();
						connection.run();
					} catch (final IOException e) {
						logger.logExeption(e);
					} finally {
						if (socket != null) {
							try {
								socket.close();
							} catch (IOException e) {
								e.printStackTrace();
							}
						}
					}
					System.err.println(printHeader + "与服务端连接断开");
					sleeper(5000
							* (i < 100 ? 1 : (i < 500 ? 2 * i / 100 : 18)));
					System.err.printf(printHeader + "尝试重连中......，第%d次\n", ++i);
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

	public void sendFile(File file) throws IOException {
		File[] files = file.listFiles();
		if (files == null || files.length == 0) {
			return;
		}
		for (File sub : files) {
			if (sub.isDirectory()) {
				sendFile(sub);
			} else if (sub.length() > 0) {
				FileInputStream in = null;
				try {
					in = new FileInputStream(sub);
					int length = Long.valueOf(sub.length()).intValue();
					byte[] buffer = new byte[length];
					while (in.read(buffer) != -1) {
						connection.sendFile(sub.getName(), buffer);
					}
				} catch (FileNotFoundException e) {
					return;
				} finally {
					if (in != null) {
						in.close();
					}
				}
			}
		}
	}

	private void checkArgs(final AgentOptions options) {
		this.server = options.getServerName();
		if (this.server == null || this.server.isEmpty()) {
			throw new IllegalArgumentException("server arg is required.");
		}
		this.classDir = options.getClassDumpDir();
		if (this.classDir == null || this.classDir.isEmpty()) {
			throw new IllegalArgumentException("classdumpdir is required.");
		}
		this.module = options.getModuleName();
		this.commit = options.getCommit();
	}

	private void setHeartbeatThread() {
		heartbeat = new Thread(new Runnable() {
			@Override
			public void run() {
				while (running) {
					try {
						long cost = System.currentTimeMillis() - last;
						if (cost >= interval) {
							System.out.println(printHeader
									+ "cost > interval: sendHeartbeat");
							connection.sendHeartbeat();
							setLastSend();
							synchronized (heartbeat) {
								System.out.printf(printHeader + "sleep: %sms%n",
										interval);
								heartbeat.wait(interval);
							}
						} else {
							long timeout = interval - cost;
							System.out.println(printHeader
									+ "cost <= interval sleep: " + timeout);
							synchronized (heartbeat) {
								heartbeat.wait(timeout);
							}
						}
					} catch (InterruptedException e) {
						e.printStackTrace();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		});
		heartbeat.setName(getClass().getName() + "heartbeat");
		heartbeat.setDaemon(true);
	}

}
