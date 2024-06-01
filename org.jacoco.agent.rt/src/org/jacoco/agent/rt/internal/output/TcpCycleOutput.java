/*******************************************************************************
 * Copyright (c) 2009, 2023 Mountainminds GmbH & Co. KG and Contributors
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

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author caoji 通过TCP连接到服务端，定时或按需上报覆盖率数据
 */
public class TcpCycleOutput implements IAgentOutput {

	private final IExceptionLogger logger;

	private AgentOptions options;

	private TcpConnection connection;

	private Thread worker;

	private Thread heartbeatThread;

	/**
	 * 默认的环境变量名
	 */
	private final String appPath = "appPath";

	private final String master = "master";
	private final String feature = "feature/";

	/**
	 * 需要排除的白名单
	 */
	private final Set<String> ignoreSet = new HashSet<String>() {
		{
			add("otel-extension.jar");
		}
	};

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

	private String product, project, service, branch, commit, classDir, gitUrl,
			jarpath;

	private File jarFile = null;

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
		this.options = options;
		// checkArgs();
		// initMatcher();
		worker = new Thread(new Runnable() {
			@Override
			public void run() {

				try {
					checkArgs();
//					if (master.equals(branch) || branch.startsWith(feature)) {
					if (master.equals(branch)) {
						options.setIncludes("-");
						options.setExcludes("*");
						AgentOptions.print.printf(
								"The current branch [%s] do not need to collect coverage data\n",
								branch);
						return;
					} else {
						AgentOptions.print.printf(
								"The current branch [%s] needs to collect coverage data\n",
								branch);
					}
				} catch (IllegalArgumentException e) {
					options.setIncludes("-");
					options.setExcludes("*");
					AgentOptions.print.printf("boot args error: %s\n", options);
					e.printStackTrace(AgentOptions.print);
					return;
				} finally {
					initMatcher();
				}

				int i = 0;
				while (running) {
					Socket socket = null;
					try {
						socket = createSocket(options);
						AgentOptions.print.printf(
								"%s already connected server%s%n",
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
								branch, commit, classDir, gitUrl, jarFile);
						// connection.sendJarClasses(jarFile);
						connection.setMatcher(includes, excludes);
						heartbeatThread.start();
						i = 0;
						connection.run();
					} catch (final IOException e) {
						if (e instanceof SocketException) {
							if ("Connection reset".equals(e.getMessage())) {
								AgentOptions.print.println(
										"SocketException: " + e.getMessage());
							}
						}
					} finally {
						if (socket != null) {
							try {
								socket.close();
								heartbeatThread.interrupt();
							} catch (IOException e) {
								e.printStackTrace(AgentOptions.print);
							}
						}
					}
					int millis = 10 * 1000
							* (i < 30 ? 1 : (i < 100 ? 2 * i / 30 : 18));
					AgentOptions.print.printf(
							"The connection to the server has been disconnected and is about to sleep for %s s\n",
							millis / 1000);
					sleeper(millis);
					if (running) {
						AgentOptions.print.printf(
								"Try to reconnect to -> %s:%s ......, the %d times\n",
								options.getAddress(), options.getPort(), ++i);
					}
				}
			}
		});
		worker.setName(getClass().getName());
		worker.setDaemon(true);
		worker.start();
	}

	public void shutdown() throws Exception {
		AgentOptions.print.println("shutdown");
		running = false;
		if (connection != null) {
			connection.close();
		}
		if (AgentOptions.print != null) {
			AgentOptions.print.close();
		}
		worker.join();
	}

	public void writeExecutionData(final boolean reset) throws IOException {
		if (connection != null) {
			connection.writeExecutionData(reset);
		}
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
			e.printStackTrace(AgentOptions.print);
			Thread.currentThread().interrupt();
		}
	}

	private void checkArgs() {
		// String includes = assertGetEnv(AgentOptions.INCLUDES);
		// if ("*".equals(includes)) {
		// throw new IllegalArgumentException(
		// AgentOptions.INCLUDES + " is required.");
		// }
		assertGetEnv(AgentOptions.ADDRESS);
		classDir = getArg(AgentOptions.CLASSDUMPDIR, AgentOptions.TEMPPATH);
		branch = assertGetEnv(AgentOptions.BRANCH);
		commit = assertGetEnv(AgentOptions.COMMIT);
		interval = options.getHeartbeat();

		gitUrl = assertGetEnv(AgentOptions.GITURL);
		if (!gitUrl.toLowerCase().endsWith(".git")) {
			gitUrl += ".git";
		}
		analyzeGitUrl(gitUrl);
		service = assertGetEnv(AgentOptions.SERVICE);
		/* jarpath可指定被测服务的jar包地址，不传时，默认到/app目录下查找 */
		jarpath = getArg(AgentOptions.JARPATH, System.getenv().get(appPath));
		if ("".equals(jarpath)) {
			File jarParent = new File("/app");
			if (jarParent.exists()) {
				File[] subs = jarParent.listFiles();
				List<File> jars = new ArrayList<File>();
				for (File sub : subs) {
					String name = sub.getName();
					if (!sub.isDirectory() && name.endsWith(".jar")
							&& !ignoreSet.contains(name)) {
						jars.add(sub);
					}
				}
				if (jars.size() == 1) {
					jarFile = jars.get(0);
				} else if (jars.size() > 1) {
					jarFile = getJarByList(jars);
				}
			} else {
				throw new IllegalArgumentException("/app folder is not exist");
			}
		} else {
			jarFile = new File(jarpath);
		}
		if (jarFile == null || !jarFile.exists()) {
			throw new IllegalArgumentException(
					"Jar file is not exist: " + jarpath);
		}
	}

	private String assertGetEnv(String key) {
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
				AgentOptions.print
						.println("get " + envKey + " from ENV: " + value);
			}
		}
		return value;
	}

	private String getArg(String key, String defaultValue) {
		String value = options.getOptions().get(key);
		if (value == null) {
			value = System.getenv().get(key);
		}
		if (value == null) {
			if (defaultValue == null || !defaultValue.endsWith(".jar")) {
				defaultValue = "";
			}
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
	private void initMatcher() {
		includes = new WildcardMatcher(options.getIncludes().replace('.', '/'));
		excludes = new WildcardMatcher(options.getExcludes().replace('.', '/'));
	}

	private File getJarByList(List<File> files) {
		File jar = null;
		for (File file : files) {
			if (file.getName().contains("-starter-")) {
				if (jar == null) {
					jar = file;
				} else {
					throw new IllegalArgumentException(
							"More than one jar in /app folder: "
									+ Arrays.toString(files.toArray()));
				}
			}
		}
		return jar;
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
				AgentOptions.print.printf("Heart beat interval %ss.%n",
						cycle.interval);
				while (socket.isConnected()) {
					long cost = System.currentTimeMillis()
							- cycle.heartbeat.get();
					if (cost >= interval) {
						AgentOptions.print.println("♥");
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
				AgentOptions.print.println("Heart beat stopped%n");
			} catch (Exception e) {
				if (!(e instanceof InterruptedException)) {
					e.printStackTrace(AgentOptions.print);
				}
			}
		}
	}

}
