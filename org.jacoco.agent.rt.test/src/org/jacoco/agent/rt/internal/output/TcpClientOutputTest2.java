/*******************************************************************************
 * Copyright (c) 2009, 2022 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Brock Janiczak - initial API and implementation
 *    Marc R. Hoffmann - migration to mock socket
 *
 *******************************************************************************/
package org.jacoco.agent.rt.internal.output;

import org.jacoco.agent.rt.internal.ExceptionRecorder;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.runtime.*;
import org.junit.Before;
import org.junit.Test;

import java.io.*;
import java.net.Socket;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Unit tests for {@link TcpClientOutput}.
 */
public class TcpClientOutputTest2 {

	private ExceptionRecorder logger;

	private IAgentOutput controller;

	private RuntimeData data;

	private Socket socket;

	@Before
	public void setup() throws Exception {
		logger = new ExceptionRecorder();
		controller = new TcpCycleOutput(logger) {
			@Override
			protected Socket createSocket(AgentOptions options)
					throws IOException {
				Socket newSocket = new Socket(options.getAddress(),
						options.getPort());
				TcpClientOutputTest2.this.socket = newSocket;
				return newSocket;
			}
		};
		data = new RuntimeData();
		String arg1 = "service=dv-gateway,branch=develop,commit=15b505394684aaf06785edb11b87e356ceca609c,"
				// String arg =
				// "service=dv-gateway,branch=develop,commit=148eca009a9a585982eebde68e6f24f5abaa174e,"
				+ "classdumpdir=target/jk/classes,address=localhost,"
				// +
				// "giturl=http://gitlab.jkservice.org/jkstack/dv/dv-back-end";
				+ "giturl=ssh://git@gitlab.jkservice.org:2222/jkstack/dv/dv-back-end.git";
		// 192.168.4.21
		// String arg =
		// "classdumpdir=target/jk/classes,output=tcpcycle,branch=feature/zpc_1026494,commit=27de3182,address=192.168.31.53";
		String arg = "includes=cn.devops.*,"
				+ "giturl=http://gitlab.jkservice.org/jkstack/dsp/dsp-backend.git,"
				+ "service=domain-appstore," + "branch=refactor-Dockerfiles,"
				+ "commit=7f34843b,"
				+ "classdumpdir=target/jk/classes/product1,"
				+ "address=192.168.31.53";
		arg = ""
				// + "includes=cn.devops.*,"
				+ "giturl=ssh://git@bitbucket.kucoin.net/devops/taskpointanalyze.git,"
				+ "service=task,branch=master,"
				+ "commit=610948aafc8433bc61b6397e6c27e3553bff96d4,"
				+ "classdumpdir=target/jk/classes/product1,"
				+ "jarpath=C:/Users/caoji/Desktop/agentTest/margin-fund/margin-fund-starter.jar,"
				+ "address=192.168.2.107";
		arg = ""
				// + "includes=cn.devops.*,"
				+ "giturl=ssh://git@bitbucket.kucoin.net/kcmg/margin-fund.git,"
				+ "service=margin-fund,branch=release-code_test_v2,"
				+ "commit=95dff3ed,"
				+ "classdumpdir=target/jk/classes/product1,"
				+ "jarpath=C:/Users/caoji/Desktop/agentTest/margin-fund/margin-fund-starter.jar,"
				+ "address=192.168.2.107";
		// kcmg|margin-fund|margin-fund|release-code_test_v2|95dff3ed|ssh://git@bitbucket.kucoin.net/kcmg/margin-fund.git
		controller.startup(new AgentOptions(arg), data);
	}

	@Test
	public void testJarFile() throws Exception {

		String path = "C:/Users/caoji/Desktop/agentTest/margin-fund/margin-fund-starter.jar";
		File file = new File(path);

		final File workDir = File.createTempFile("unjar_", "",
				new File(System.getProperty("user.dir")));
		workDir.delete();
		workDir.mkdirs();
		if (!workDir.isDirectory()) {
			System.err.println("Mkdirs failed to create " + workDir);
			System.exit(-1);
		}

		// 解压jar文件
		unJar(file, workDir);

	}

	public static void unJar(File jarFile, File toDir) throws IOException {
		JarFile jar = new JarFile(jarFile);
		try {
			Enumeration<JarEntry> entries = jar.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				String name = entry.getName();
				if (!entry.isDirectory()
						&& (name.endsWith(".class") || name.endsWith(".jar"))) {
					InputStream in = jar.getInputStream(entry);
					try {
						File file = new File(toDir, name);
						if (!file.getParentFile().mkdirs()) {
							if (!file.getParentFile().isDirectory()) {
								throw new IOException("Mkdirs failed to create "
										+ file.getParentFile().toString());
							}
						}
						OutputStream out = new FileOutputStream(file);
						try {
							int size = (int) Math.min(entry.getSize(),
									Integer.MAX_VALUE);
							byte[] buffer = new byte[size];
							int i;
							while ((i = in.read(buffer)) != -1) {
								out.write(buffer, 0, i);
							}
						} finally {
							out.close();
						}
					} finally {
						in.close();
					}
				}
			}
		} finally {
			jar.close();
		}
	}

	@Test
	public void testAsyncWriteExecutionData() throws Exception {
		ExecutionData foo = data.getExecutionData(Long.valueOf(0x12345678),
				"Foo", 42);
		foo.getProbes()[0] = true;
		data.setSessionId("stubid");
		// ExtraInfo extraInfo = new ExtraInfo("jk", "dms", "12345");
		// data.setExtraInfo(extraInfo.toString());

		// TcpCycleOutput tcpCycle = (TcpCycleOutput) this.controller;
		// File file = new File("C:/Users/caoji/Desktop/security");
		// tcpCycle.sendFile(file);

		synchronized (this.controller) {
			this.controller.wait();
		}

		this.controller.writeExecutionData(false);

		for (int i = 2; i < 10; i += 2) {
			foo.getProbes()[i] = true;
		}
		this.controller.writeExecutionData(false);
		System.out.println("End");
		socket.close();
	}

}
