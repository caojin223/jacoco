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
import org.jacoco.core.runtime.WildcardMatcher;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author caoji 通过TCP连接到服务端，定时或按需上报覆盖率数据
 */
public class TcpCycleOutput implements IAgentOutput {

	private final IExceptionLogger logger;

	private TcpConnection connection;

	private Thread worker;

	private Thread heartbeatThread;

	private final String printHeader = "-----jacoco----------> ";

	private volatile boolean running = true;

	/**
	 * 标记最后发送时间，用于发送心跳
	 */
	private final AtomicLong heartbeat = new AtomicLong(
			System.currentTimeMillis());

	/**
	 * 心跳间隔
	 */
	private int interval;

	/** Matcher，用于判断dump到本地的classes文件，是否需要发送给远端服务器 */
	private WildcardMatcher includes;
	private WildcardMatcher excludes;

	private String product, project, service, branch, commit, classDir, gitUrl;

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
		initMatcher(options);
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
						heartbeatThread = new Thread(
								new Heartbeat(socket, TcpCycleOutput.this));
						heartbeatThread
								.setName(getClass().getName() + "_heartbeat");
						heartbeatThread.setDaemon(true);
						socket.setKeepAlive(true);
						connection = new TcpConnection(socket, data);
						connection.init();
						connection.setHeartbeat(heartbeat);
						// 用于通知服务端初始化项目信息，如拉取代码等
						connection.sendProjectInfo(product, project, service,
								branch, commit, classDir, gitUrl);
						connection.setMatcher(includes, excludes);
						heartbeatThread.start();
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
								heartbeatThread.interrupt();
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

	private void checkArgs(final AgentOptions options) {
		String includes = assertGetEnv(AgentOptions.INCLUDES, options);
		if ("*".equals(includes)) {
			throw new IllegalArgumentException(
					AgentOptions.INCLUDES + " is required.");
		}
		assertGetEnv(AgentOptions.ADDRESS, options);
		classDir = getArg(AgentOptions.CLASSDUMPDIR, options, "classes");
		branch = assertGetEnv(AgentOptions.BRANCH, options);
		commit = assertGetEnv(AgentOptions.COMMIT, options);
		interval = options.getHeartbeat();

		gitUrl = assertGetEnv(AgentOptions.GITURL, options);
		if (!gitUrl.toLowerCase().endsWith(".git")) {
			gitUrl += ".git";
		}
		analyzeGitUrl(gitUrl);
		service = assertGetEnv(AgentOptions.SERVICE, options);
	}

	private String assertGetEnv(String key, AgentOptions options) {
		String value = options.getOptions().get(key);
		if (value == null) {
			String envKey = options.envMap.get(key);
			if (envKey != null) {
				value = System.getenv().get(envKey);
			}
			if (value == null) {
				String err = envKey == null ? key : key + " or " + envKey;
				throw new IllegalArgumentException(err + " is required.");
			} else {
				options.getOptions().put(key, value);
				System.out.println(
						printHeader + "get " + envKey + " from ENV: " + value);
			}
		}
		return value;
	}

	private String getArg(String key, AgentOptions options,
			String defaultValue) {
		String value = options.getOptions().get(key);
		if (value == null) {
			value = System.getenv().get(key);
		}
		if (value == null) {
			options.getOptions().put(key, defaultValue);
			value = defaultValue;
		}
		return value;
	}

	private void analyzeGitUrl(String gitUrl) {
		String[] split = gitUrl.split("/");
		if (split.length < 5) {
			throw new IllegalArgumentException("Invalid git url: " + gitUrl);
		}
		product = split[split.length - 2];
		String project = split[split.length - 1];
		this.project = project.substring(0, project.length() - 4);
	}

	/** 初始化Matcher，用于判断dump到本地的classes文件，是否需要发送给远端服务器 */
	private void initMatcher(final AgentOptions options) {
		includes = new WildcardMatcher(options.getIncludes().replace('.', '/'));
		excludes = new WildcardMatcher(options.getExcludes().replace('.', '/'));
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
				while (socket.isConnected()) {
					long cost = System.currentTimeMillis()
							- cycle.heartbeat.get();
					if (cost >= interval) {
						System.out.println("♥");
						cycle.connection.sendHeartbeat();
						synchronized (this) {
							wait(interval);
						}
					} else {
						long remaining = interval - cost;
						synchronized (this) {
							wait(remaining);
						}
					}
				}
				System.out.println(cycle.printHeader + "心跳停止%n");
			} catch (Exception e) {
				if (!(e instanceof InterruptedException)) {
					e.printStackTrace();
				}
			}
		}
	}

}
