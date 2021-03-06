/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.common.channel;

import java.io.IOException;

import org.apache.sshd.common.Channel;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.Session;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.future.CloseFuture;
import org.apache.sshd.common.future.DefaultCloseFuture;
import org.apache.sshd.common.future.SshFuture;
import org.apache.sshd.common.future.SshFutureListener;
import org.apache.sshd.common.util.Buffer;
import org.apache.sshd.common.util.BufferUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sshd.common.util.LogUtils;

/**
 * TODO Add javadoc
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public abstract class AbstractChannel implements Channel {

    public static final int DEFAULT_WINDOW_SIZE = 0x200000;
    public static final int DEFAULT_PACKET_SIZE = 0x8000;

    protected final Log log = LogFactory.getLog(getClass());
    protected final Object lock = new Object();
    protected final Window localWindow = new Window(this, null, getClass().getName().contains(".client."), true);
    protected final Window remoteWindow = new Window(this, null, getClass().getName().contains(".client."), false);
    protected Session session;
    protected int id;
    protected int recipient;
    protected boolean eof;
    protected final CloseFuture closeFuture = new DefaultCloseFuture(lock);
    protected boolean closing;

    public int getId() {
        return id;
    }

    public int getRecipient() {
        return recipient;
    }

    public Window getLocalWindow() {
        return localWindow;
    }

    public Session getSession() {
        return session;
    }

    public void handleRequest(Buffer buffer) throws IOException {
        throw new IllegalStateException();
    }

    public void init(Session session, int id) {
        this.session = session;
        this.id = id;
        configureWindow();
    }

    public CloseFuture close(boolean immediately) {
        try {
            synchronized (lock) {
                if (immediately) {
                    LogUtils.info(log,"Closing channel {0} immediately", id);
                    closeFuture.setClosed();
                    lock.notifyAll();
                    session.unregisterChannel(this);
                } else {
                    if (!closing) {
                        closing = true;
                        LogUtils.info(log,"Send SSH_MSG_CHANNEL_CLOSE on channel {0}", id);
                        Buffer buffer = session.createBuffer(SshConstants.Message.SSH_MSG_CHANNEL_CLOSE, 0);
                        buffer.putInt(recipient);
                        session.writePacket(buffer);
                    }
                }
            }
        } catch (IOException e) {
            session.exceptionCaught(e);
            closeFuture.setClosed();
        }
        return closeFuture;
    }

    public void handleClose() throws IOException {
        LogUtils.info(log,"Received SSH_MSG_CHANNEL_CLOSE on channel {0}", id);
        synchronized (lock) {
            close(false).setClosed();
            doClose();
            lock.notifyAll();
        }
    }

    protected void doClose() {
    }

    public void handleData(Buffer buffer) throws IOException {
        int len = buffer.getInt();
        if (len < 0 || len > 32768) {
            throw new IllegalStateException("Bad item length: " + len);
        }
        LogUtils.debug(log,"Received SSH_MSG_CHANNEL_DATA on channel {0}", id);
        if (log.isTraceEnabled()) {
            log.trace("Received channel data: "+BufferUtils.printHex(buffer.array(), buffer.rpos(), len));
        }
        doWriteData(buffer.array(), buffer.rpos(), len);
    }

    public void handleExtendedData(Buffer buffer) throws IOException {
        int ex = buffer.getInt();
        // Only accept extended data for stderr
        if (ex != 1) {
            LogUtils.info(log,"Send SSH_MSG_CHANNEL_FAILURE on channel {0}", id);
            buffer = session.createBuffer(SshConstants.Message.SSH_MSG_CHANNEL_FAILURE, 0);
            buffer.putInt(recipient);
            session.writePacket(buffer);
            return;
        }
        int len = buffer.getInt();
        if (len < 0 || len > 32768) {
            throw new IllegalStateException("Bad item length: " + len);
        }
        LogUtils.debug(log,"Received SSH_MSG_CHANNEL_EXTENDED_DATA on channel {0}", id);
        if (log.isTraceEnabled()) {
            log.trace("Received channel extended data: "+ BufferUtils.printHex(buffer.array(), buffer.rpos(), len));
        }
        doWriteExtendedData(buffer.array(), buffer.rpos(), len);
    }

    public void handleEof() throws IOException {
        LogUtils.info(log,"Received SSH_MSG_CHANNEL_EOF on channel {0}", id);
        synchronized (lock) {
            eof = true;
            lock.notifyAll();
        }
    }

    public void handleWindowAdjust(Buffer buffer) throws IOException {
        LogUtils.info(log,"Received SSH_MSG_CHANNEL_WINDOW_ADJUST on channel {0}", id);
        int window = buffer.getInt();
        remoteWindow.expand(window);
    }

    public void handleFailure() throws IOException {
        LogUtils.info(log,"Received SSH_MSG_CHANNEL_FAILURE on channel {0}", id);
        // TODO: do something to report failed requests?
    }

    protected abstract void doWriteData(byte[] data, int off, int len) throws IOException;

    protected abstract void doWriteExtendedData(byte[] data, int off, int len) throws IOException;

    protected void sendEof() throws IOException {
        LogUtils.info(log,"Send SSH_MSG_CHANNEL_EOF on channel {0}", id);
        Buffer buffer = session.createBuffer(SshConstants.Message.SSH_MSG_CHANNEL_EOF, 0);
        buffer.putInt(recipient);
        session.writePacket(buffer);
    }

    protected void configureWindow() {
        int window = session.getIntProperty(FactoryManager.WINDOW_SIZE, DEFAULT_WINDOW_SIZE);
        int packet = session.getIntProperty(FactoryManager.MAX_PACKET_SIZE, DEFAULT_PACKET_SIZE);
        localWindow.init(window, packet);
    }

    protected void sendWindowAdjust(int len) throws IOException {
        LogUtils.info(log,"Send SSH_MSG_CHANNEL_WINDOW_ADJUST on channel {0}", id);
        Buffer buffer = session.createBuffer(SshConstants.Message.SSH_MSG_CHANNEL_WINDOW_ADJUST, 0);
        buffer.putInt(recipient);
        buffer.putInt(len);
        session.writePacket(buffer);
    }
}
