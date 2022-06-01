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
				+ "giturl=http://gitlab.jkservice.org/jkstack/qa/test-platform/devops-security-master,"
				+ "service=product_management," + "branch=master,"
				+ "commit=d9eaca473353bd2a9aa49a3522c0dfe4b513c0d0,"
				+ "classdumpdir=target/jk/classes/product1,"
				+ "address=192.168.31.231";
		controller.startup(new AgentOptions(arg), data);
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
		// File file = new File("C:\\Users\\caoji\\Desktop\\security");
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
