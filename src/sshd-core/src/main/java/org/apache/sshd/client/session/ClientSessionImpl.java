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
package org.apache.sshd.client.session;

import java.io.IOException;
import java.net.SocketAddress;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;

import org.apache.mina.core.session.IoSession;
import org.apache.sshd.ClientChannel;
import org.apache.sshd.ClientSession;
import org.apache.sshd.SshClient;
import org.apache.sshd.client.ClientFactoryManager;
import org.apache.sshd.client.ServerKeyVerifier;
import org.apache.sshd.client.UserAuth;
import org.apache.sshd.client.auth.UserAuthAgent;
import org.apache.sshd.client.auth.UserAuthPassword;
import org.apache.sshd.client.auth.UserAuthPublicKey;
import org.apache.sshd.client.channel.AbstractClientChannel;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ChannelSubsystem;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.DefaultAuthFuture;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.common.*;
import org.apache.sshd.common.future.CloseFuture;
import org.apache.sshd.common.future.SshFutureListener;
import org.apache.sshd.common.session.AbstractSession;
import org.apache.sshd.common.util.Buffer;
import org.apache.sshd.common.util.LogUtils;
import org.apache.sshd.server.channel.OpenChannelException;
import org.apache.sshd.client.PumpingMethod;

/**
 * TODO Add javadoc
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class ClientSessionImpl extends AbstractSession implements ClientSession {

    public enum State {
        ReceiveKexInit, Kex, ReceiveNewKeys, AuthRequestSent, WaitForAuth, UserAuth, Running, Unknown
    }

    private State state = State.ReceiveKexInit;
    private UserAuth userAuth;
    private AuthFuture authFuture;
    private PumpingMethod pumpingMethod=PumpingMethod.OFF;
    /**
     * For clients to store their own metadata
     */
    private Map<Object, Object> metadataMap = new HashMap<Object, Object>();

    public ClientSessionImpl(FactoryManager client, IoSession session) throws Exception {
        super(client, session);
        log.info("Session created...");
        sendClientIdentification();
        sendKexInit();
    }

    public ClientFactoryManager getClientFactoryManager() {
        return (ClientFactoryManager) factoryManager;
    }

    public KeyExchange getKex() {
        return kex;
    }

    public AuthFuture authAgent(String username) throws IOException {
        synchronized (lock) {
            if (closeFuture.isClosed()) {
                throw new IllegalStateException("Session is closed");
            }
            if (authed) {
                throw new IllegalStateException("User authentication has already been performed");
            }
            if (userAuth != null) {
                throw new IllegalStateException("A user authentication request is already pending");
            }
            waitFor(CLOSED | WAIT_AUTH, 0);
            if (closeFuture.isClosed()) {
                throw new IllegalStateException("Session is closed");
            }
            authFuture = new DefaultAuthFuture(lock);
            userAuth = new UserAuthAgent(this, username);
            setState(ClientSessionImpl.State.UserAuth);
            return authFuture;
        }
    }

    public AuthFuture authPassword(String username, String password) throws IOException {
        synchronized (lock) {
            if (closeFuture.isClosed()) {
                throw new IllegalStateException("Session is closed");
            }
            if (authed) {
                throw new IllegalStateException("User authentication has already been performed");
            }
            if (userAuth != null) {
                throw new IllegalStateException("A user authentication request is already pending");
            }
            waitFor(CLOSED | WAIT_AUTH, 0);
            if (closeFuture.isClosed()) {
                throw new IllegalStateException("Session is closed");
            }
            authFuture = new DefaultAuthFuture(lock);
            userAuth = new UserAuthPassword(this, username, password);
            setState(ClientSessionImpl.State.UserAuth);
            return authFuture;
        }
    }

    public AuthFuture authPublicKey(String username, KeyPair key) throws IOException {
        synchronized (lock) {
            if (closeFuture.isClosed()) {
                throw new IllegalStateException("Session is closed");
            }
            if (authed) {
                throw new IllegalStateException("User authentication has already been performed");
            }
            if (userAuth != null) {
                throw new IllegalStateException("A user authentication request is already pending");
            }
            waitFor(CLOSED | WAIT_AUTH, 0);
            if (closeFuture.isClosed()) {
                throw new IllegalStateException("Session is closed");
            }
            authFuture = new DefaultAuthFuture(lock);
            userAuth = new UserAuthPublicKey(this, username, key);
            setState(ClientSessionImpl.State.UserAuth);
            return authFuture;
        }
    }

    public ClientChannel createChannel(String type) throws Exception {
        return createChannel(type, null);
    }

    public ClientChannel createChannel(String type, String subType) throws Exception {
        if (ClientChannel.CHANNEL_SHELL.equals(type)) {
            return createShellChannel();
        } else if (ClientChannel.CHANNEL_EXEC.equals(type)) {
            return createExecChannel(subType);
        } else if (ClientChannel.CHANNEL_SUBSYSTEM.equals(type)) {
            return createSubsystemChannel(subType);
        } else {
            throw new IllegalArgumentException("Unsupported channel type " + type);
        }
    }

    public ChannelShell createShellChannel() throws Exception {
        ChannelShell channel = new ChannelShell();
        if(pumpingMethod==PumpingMethod.PARENT || pumpingMethod==PumpingMethod.SELF)
            channel.setPumpingMethod(PumpingMethod.PARENT);
        registerChannel(channel);
        return channel;
    }

    public ChannelExec createExecChannel(String command) throws Exception {
        ChannelExec channel = new ChannelExec(command);
        if(pumpingMethod==PumpingMethod.PARENT || pumpingMethod==PumpingMethod.SELF)
            channel.setPumpingMethod(PumpingMethod.PARENT);
        registerChannel(channel);
        return channel;
    }

    public ChannelSubsystem createSubsystemChannel(String subsystem) throws Exception {
        ChannelSubsystem channel = new ChannelSubsystem(subsystem);
        if(pumpingMethod==PumpingMethod.PARENT || pumpingMethod==PumpingMethod.SELF)
            channel.setPumpingMethod(PumpingMethod.PARENT);
        registerChannel(channel);
        return channel;
    }

    @Override
    public CloseFuture close(boolean immediately) {
        synchronized (lock) {
            if (authFuture != null && !authFuture.isDone()) {
                authFuture.setException(new SshException("Session is closed"));
            }
            return super.close(immediately);
        }
    }

    protected void handleMessage(Buffer buffer) throws Exception {
        synchronized (lock) {
            doHandleMessage(buffer);
        }
    }

    protected void doHandleMessage(Buffer buffer) throws Exception {
        SshConstants.Message cmd = buffer.getCommand();
        LogUtils.debug(log,"Received packet {0}", cmd);
        switch (cmd) {
            case SSH_MSG_DISCONNECT: {
                int code = buffer.getInt();
                String msg = buffer.getString();
                LogUtils.info(log,"Received SSH_MSG_DISCONNECT (reason={0}, msg={1})", code, msg);
                close(false);
                break;
            }
            case SSH_MSG_UNIMPLEMENTED: {
                int code = buffer.getInt();
                LogUtils.info(log,"Received SSH_MSG_UNIMPLEMENTED #{0}", code);
                break;
            }
            case SSH_MSG_DEBUG: {
                boolean display = buffer.getBoolean();
                String msg = buffer.getString();
                LogUtils.info(log,"Received SSH_MSG_DEBUG (display={0}) '{1}'", display, msg);
                break;
            }
            case SSH_MSG_IGNORE:
                log.info("Received SSH_MSG_IGNORE");
                break;
            default:
                switch (state) {
                    case ReceiveKexInit:
                        if (cmd != SshConstants.Message.SSH_MSG_KEXINIT) {
                            log.error("Ignoring command " + cmd + " while waiting for " + SshConstants.Message.SSH_MSG_KEXINIT);
                            break;
                        }
                        log.info("Received SSH_MSG_KEXINIT");
                        receiveKexInit(buffer);
                        negociate();
                        kex = NamedFactory.Utils.create(factoryManager.getKeyExchangeFactories(), negociated[SshConstants.PROPOSAL_KEX_ALGS]);
                        kex.init(this, serverVersion.getBytes(), clientVersion.getBytes(), I_S, I_C);
                        setState(State.Kex);
                        break;
                    case Kex:
                        buffer.rpos(buffer.rpos() - 1);
                        if (kex.next(buffer)) {
                            checkHost();
                            sendNewKeys();
                            setState(State.ReceiveNewKeys);
                        }
                        break;
                    case ReceiveNewKeys:
                        if (cmd != SshConstants.Message.SSH_MSG_NEWKEYS) {
                            disconnect(SshConstants.SSH2_DISCONNECT_PROTOCOL_ERROR, "Protocol error: expected packet SSH_MSG_NEWKEYS, got " + cmd);
                            return;
                        }
                        log.info("Received SSH_MSG_NEWKEYS");
                        receiveNewKeys(false);
                        sendAuthRequest();
                        setState(State.AuthRequestSent);
                        break;
                    case AuthRequestSent:
                        if (cmd != SshConstants.Message.SSH_MSG_SERVICE_ACCEPT) {
                            disconnect(SshConstants.SSH2_DISCONNECT_PROTOCOL_ERROR, "Protocol error: expected packet SSH_MSG_SERVICE_ACCEPT, got " + cmd);
                            return;
                        }
                        setState(State.WaitForAuth);
                        break;
                    case WaitForAuth:
                        // We're waiting for the client to send an authentication request
                        // TODO: handle unexpected incoming packets
                        break;
                    case UserAuth:
                        if (userAuth == null) {
                            throw new IllegalStateException("State is userAuth, but no user auth pending!!!");
                        }
                        buffer.rpos(buffer.rpos() - 1);
                        switch (userAuth.next(buffer)) {
                             case Success:
                                 authFuture.setAuthed(true);
                                 authed = true;
                                 setState(State.Running);
                                 break;
                             case Failure:
                                 authFuture.setAuthed(false);
                                 userAuth = null;
                                 setState(State.WaitForAuth);
                                 break;
                             case Continued:
                                 break;
                        }
                        break;
                    case Running:
                        switch (cmd) {
                            case SSH_MSG_CHANNEL_OPEN:
                                channelOpen(buffer);
                                break;
                            case SSH_MSG_CHANNEL_OPEN_CONFIRMATION:
                                channelOpenConfirmation(buffer);
                                break;
                            case SSH_MSG_CHANNEL_OPEN_FAILURE:
                                channelOpenFailure(buffer);
                                break;
                            case SSH_MSG_CHANNEL_REQUEST:
                                channelRequest(buffer);
                                break;
                            case SSH_MSG_CHANNEL_DATA:
                                channelData(buffer);
                                break;
                            case SSH_MSG_CHANNEL_EXTENDED_DATA:
                                channelExtendedData(buffer);
                                break;
                            case SSH_MSG_CHANNEL_FAILURE:
                                channelFailure(buffer);
                                break;
                            case SSH_MSG_CHANNEL_WINDOW_ADJUST:
                                channelWindowAdjust(buffer);
                                break;
                            case SSH_MSG_CHANNEL_EOF:
                                channelEof(buffer);
                                break;
                            case SSH_MSG_CHANNEL_CLOSE:
                                channelClose(buffer);
                                break;
                            default:
                                throw new IllegalStateException("Unsupported command: " + cmd);
                        }
                        break;
                    default:
                        throw new IllegalStateException("Unsupported state: " + state);
                }
        }
    }

    public int waitFor(int mask, long timeout) {
        long t = 0;
        synchronized (lock) {
            for (;;) {
                int cond = 0;
                if (closeFuture.isClosed()) {
                    cond |= CLOSED;
                }
                if (authed) {
                    cond |= AUTHED;
                }
                if (state == State.WaitForAuth) {
                    cond |= WAIT_AUTH;
                }
                if ((cond & mask) != 0) {
                    return cond;
                }
                if (timeout > 0) {
                    if (t == 0) {
                        t = System.currentTimeMillis() + timeout;
                    } else {
                        timeout = t - System.currentTimeMillis();
                        if (timeout <= 0) {
                            cond |= TIMEOUT;
                            return cond;
                        }
                    }
                }
                try {
                    if (timeout > 0) {
                        lock.wait(timeout);
                    } else {
                        lock.wait();
                    }
                } catch (InterruptedException e) {
                    // Ignore
                }
            }
        }
    }

    public void setState(State newState) {
        synchronized (lock) {
            this.state = newState;
            lock.notifyAll();
        }
    }

    protected boolean readIdentification(Buffer buffer) throws IOException {
        serverVersion = doReadIdentification(buffer);
        if (serverVersion == null) {
            return false;
        }
        LogUtils.info(log,"Server version string: {0}", serverVersion);
        if (!serverVersion.startsWith("SSH-2.0-")) {
            throw new SshException(SshConstants.SSH2_DISCONNECT_PROTOCOL_VERSION_NOT_SUPPORTED,
                                   "Unsupported protocol version: " + serverVersion);
        }
        return true;
    }

    private void sendClientIdentification() {
        clientVersion = "SSH-2.0-" + getFactoryManager().getVersion();
        sendIdentification(clientVersion);
    }

    private void sendKexInit() throws Exception {
        clientProposal = createProposal(KeyPairProvider.SSH_RSA + "," + KeyPairProvider.SSH_DSS);
        I_C = sendKexInit(clientProposal);
    }

    private void receiveKexInit(Buffer buffer) throws Exception {
        serverProposal = new String[SshConstants.PROPOSAL_MAX];
        I_S = receiveKexInit(buffer, serverProposal);
    }

    private void checkHost() throws SshException {
        ServerKeyVerifier serverKeyVerifier = getClientFactoryManager().getServerKeyVerifier();

        if (serverKeyVerifier != null) {
            SocketAddress remoteAddress = ioSession.getRemoteAddress();

            if (!serverKeyVerifier.verifyServerKey(this, remoteAddress, kex.getServerKey()))
                throw new SshException("Server key did not validate");
        }
    }

    private void sendAuthRequest() throws Exception {
        log.info("Send SSH_MSG_SERVICE_REQUEST for ssh-userauth");
        Buffer buffer = createBuffer(SshConstants.Message.SSH_MSG_SERVICE_REQUEST, 0);
        buffer.putString("ssh-userauth");
        writePacket(buffer);
    }

    private void channelOpen(Buffer buffer) throws Exception {
        String type = buffer.getString();
        final int id = buffer.getInt();
        final int rwsize = buffer.getInt();
        final int rmpsize = buffer.getInt();

        LogUtils.info(log,"Received SSH_MSG_CHANNEL_OPEN {0}", type);

        if (closing) {
            Buffer buf = createBuffer(SshConstants.Message.SSH_MSG_CHANNEL_OPEN_FAILURE, 0);
            buf.putInt(id);
            buf.putInt(SshConstants.SSH_OPEN_CONNECT_FAILED);
            buf.putString("SSH server is shutting down: " + type);
            buf.putString("");
            writePacket(buf);
            return;
        }

        final Channel channel = NamedFactory.Utils.create(getFactoryManager().getChannelFactories(), type);
        if (channel == null) {
            Buffer buf = createBuffer(SshConstants.Message.SSH_MSG_CHANNEL_OPEN_FAILURE, 0);
            buf.putInt(id);
            buf.putInt(SshConstants.SSH_OPEN_UNKNOWN_CHANNEL_TYPE);
            buf.putString("Unsupported channel type: " + type);
            buf.putString("");
            writePacket(buf);
            return;
        }

        final int channelId = getNextChannelId();
        channels.put(channelId, channel);
        channel.init(this, channelId);
        channel.open(id, rwsize, rmpsize, buffer).addListener(new SshFutureListener<OpenFuture>() {
            public void operationComplete(OpenFuture future) {
                try {
                    if (future.isOpened()) {
                        Buffer buf = createBuffer(SshConstants.Message.SSH_MSG_CHANNEL_OPEN_CONFIRMATION, 0);
                        buf.putInt(id);
                        buf.putInt(channelId);
                        buf.putInt(channel.getLocalWindow().getSize());
                        buf.putInt(channel.getLocalWindow().getPacketSize());
                        writePacket(buf);
                    } else if (future.getException() != null) {
                        Buffer buf = createBuffer(SshConstants.Message.SSH_MSG_CHANNEL_OPEN_FAILURE, 0);
                        buf.putInt(id);
                        if (future.getException() instanceof OpenChannelException) {
                            buf.putInt(((OpenChannelException)future.getException()).getReasonCode());
                            buf.putString(future.getException().getMessage());
                        } else {
                            buf.putInt(0);
                            buf.putString("Error opening channel: " + future.getException().getMessage());
                        }
                        buf.putString("");
                        writePacket(buf);
                    }
                } catch (IOException e) {
                    exceptionCaught(e);
                }
            }
        });
    }

	public Map<Object, Object> getMetadataMap() {
		return metadataMap;
	}

    public PumpingMethod getPumpingMethod()
    {
        LogUtils.trace(log,"session getPumpingMethod {0}",pumpingMethod);
        return pumpingMethod;
    }

    public void setPumpingMethod(PumpingMethod pumpingMethod)
    {
        if(pumpingMethod==PumpingMethod.SELF)
            throw new IllegalArgumentException("Not Implemented. Session Pumping thread can be started here.");
        this.pumpingMethod=pumpingMethod;
    }

    public boolean pump()
    {
        log.trace("pump session");
        if (!closing)
        {
            boolean dataTransferred = false;
            for (Channel channel : channels.values())
            {
                ClientChannel clientChannel = (ClientChannel) channel;
                if (clientChannel.getPumpingMethod() == PumpingMethod.PARENT)
                {
                    dataTransferred = dataTransferred || clientChannel.pump();
                }
            }
            return dataTransferred;
        }
        return false;
    }
}
