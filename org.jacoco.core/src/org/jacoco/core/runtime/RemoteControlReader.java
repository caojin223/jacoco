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

import java.io.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataWriter;

/**
 * {@link ExecutionDataReader} with commands added for runtime remote control.
 */
public class RemoteControlReader extends ExecutionDataReader {

	private IRemoteCommandVisitor remoteCommandVisitor;

	/**
	 * 添加消息发送的对象，用于回应服务端的请求
	 */
	private RemoteControlWriter writer;

	private String classDir;

	public void setServer(String classDir) {
		this.classDir = classDir;
	}

	/** 过滤器，用于判定哪些class文件符合传输规则 */
	private WildcardMatcher includes;
	private WildcardMatcher excludes;

	public void setMatcher(WildcardMatcher includes, WildcardMatcher excludes) {
		this.includes = includes;
		this.excludes = excludes;
	}

	/**
	 * Create a new read based on the given input stream.
	 *
	 * @param input
	 *            input stream to read commands from
	 * @throws IOException
	 *             if the stream does not have a valid header
	 */
	public RemoteControlReader(final InputStream input) throws IOException {
		super(input);
	}

	public void setRemoteWriter(RemoteControlWriter writer) {
		this.writer = writer;
	}

	@Override
	protected boolean readBlock(final byte blockid) throws IOException {
		switch (blockid) {
		case RemoteControlWriter.BLOCK_CMDDUMP:
			readDumpCommand();
			return true;
		case RemoteControlWriter.BLOCK_CMDOK:
			return false;
		case ExecutionDataWriter.BLOCK_PULL_CLASSES:
			remotePullClasses();
			return true;
		default:
			return super.readBlock(blockid);
		}
	}

	/**
	 * Sets an listener for agent commands.
	 *
	 * @param visitor
	 *            visitor to retrieve agent commands
	 */
	public void setRemoteCommandVisitor(final IRemoteCommandVisitor visitor) {
		this.remoteCommandVisitor = visitor;
	}

	private void readDumpCommand() throws IOException {
		if (remoteCommandVisitor == null) {
			throw new IOException("No remote command visitor.");
		}
		final boolean dump = in.readBoolean();
		final boolean reset = in.readBoolean();
		remoteCommandVisitor.visitDumpCommand(dump, reset);
	}

	private void remotePullClasses() throws IOException {
		int length = in.readVarInt();
		byte[] buffer = readBytesByLength(length);
		// readUTF有长度限制：65536
		// final String listStr = in.readUTF();
		final String listStr = new String(buffer);
		if (writer == null) {
			return;
		}
		Set classIds = listStr.isEmpty() ? null
				: new HashSet(Arrays.asList(listStr.split("\\|")));
		File folder = new File(classDir);
		if (!folder.exists()) {
			folder.mkdirs();
		}
		sendClassFile(folder, classIds);
	}

	private void sendClassFile(File folder, Set classIds) throws IOException {
		File[] files = folder.listFiles();
		if (files == null) {
			return;
		}
		for (File sub : files) {
			if (sub.isDirectory()) {
				sendClassFile(sub, classIds);
			} else {
				long length = sub.length();
				if (length > 0) {
					if (filter(sub)) {
						String subName = sub.getName();
						String classId = getClassIdByName(subName);
						if (classIds == null || !classIds.contains(classId)) {
							FileInputStream in = null;
							try {
								in = new FileInputStream(sub);
								byte[] buffer = new byte[Long.valueOf(length)
										.intValue()];
								while (in.read(buffer) != -1) {
									writer.sendClassFile(subName, buffer);
								}
							} finally {
								if (in != null) {
									in.close();
								}
							}
						}
					}
				}
			}
		}
	}

	private String getClassIdByName(String name) {
		String[] split = name.split("\\.");
		String rst = "";
		if (split.length > 2) {
			rst = split[split.length - 2];
		}
		return rst;
	}

	private boolean filter(File classFile) {
		if (includes == null) {
			return true;
		}
		String path = classFile.getPath().replace("\\", "/");
		int offset = classDir.endsWith("/") ? 0 : 1;
		String classname = path.substring(classDir.length() + offset);
		return includes.matches(classname) && !excludes.matches(classname);
	}

	/**
	 * 远程网络会有TCP粘包拆包问题，所以需要循环多次提取数据
	 *
	 * @param length
	 *            提取的总长度
	 * @return 提取后的byte数组
	 */
	public byte[] readBytesByLength(int length) throws IOException {
		byte[] buffer, rst = new byte[length];
		int offset = 0;
		while (offset < length) {
			buffer = new byte[length - offset];
			int readLen = in.read(buffer);
			if (readLen == length && offset == 0) {
				return buffer;
			}
			System.arraycopy(buffer, 0, rst, offset, readLen);
			offset += readLen;
		}
		return rst;
	}

}
