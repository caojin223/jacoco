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
package org.jacoco.core.runtime;

/**
 * @author caoji 扩展的信息，如服务名、commit号，版本等，配合TcpClient使用
 */
public class ExtraInfo {
	private final String server, module, commit;

	public ExtraInfo(String server, String module, String commit) {
		this.server = server;
		this.module = module;
		this.commit = commit;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(server);
		if (module != null) {
			sb.append("|").append(module);
		}
		if (commit != null) {
			sb.append("|").append(commit);
		}
		return sb.toString();
	}
}
