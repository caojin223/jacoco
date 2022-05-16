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
					Socket socket = null;
					try {
						socket = createSocket(options);
						heartbeat = new Thread(new Heartbeat(socket, TcpCycleOutput.this));
						heartbeat.setName(getClass().getName() + "heartbeat");
						heartbeat.setDaemon(true);
						socket.setKeepAlive(true);
						connection = new TcpConnection(socket, data);
						data.setExtraInfo(new ExtraInfo(server, module, commit)
								.toString());
						connection.init();
						connection.sendServerName(server, module, commit,
								classDir);
						setLastSend();
						heartbeat.start();
						i = 0;
						connection.run();
					} catch (final IOException e) {
						if (e instanceof SocketException) {
							if ("Connection reset".equals(e.getMessage())) {
								System.err.println("Connection reset");
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

	static class Heartbeat implements Runnable{
		private Socket socket;
		private TcpCycleOutput cycle;

		Heartbeat(Socket socket, TcpCycleOutput cycle){
			this.socket = socket;
			this.cycle = cycle;
		}

		@Override
		public void run() {
			try {
				System.out.println("心跳包线程已启动...");
				while (true) {
					if (socket.isClosed()) {
						System.out.println("心跳包线程已停止...");
						return;
					}
					long cost = System.currentTimeMillis() - cycle.last;
					if (cost >= cycle.interval) {
						System.out.println(cycle.printHeader
								+ "cost > interval: sendHeartbeat");
						cycle.connection.sendHeartbeat();
						this.cycle.setLastSend();
						synchronized (this) {
							System.out.printf(cycle.printHeader + "sleep: %sms%n",
									cycle.interval);
							wait(cycle.interval);
						}
					} else {
						long timeout = cycle.interval - cost;
						System.out.println(cycle.printHeader
								+ "cost <= interval sleep: " + timeout);
						synchronized (this) {
							wait(timeout);
						}
					}
				}
			} catch (Exception e) {
				if (e instanceof InterruptedException) {
					System.out.println("InterruptedException: " + socket);
				} else {
					e.printStackTrace();
				}
			}
		}
	}

}
