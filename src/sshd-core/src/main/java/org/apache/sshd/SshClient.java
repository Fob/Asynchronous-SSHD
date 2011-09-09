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
package org.apache.sshd;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedList;
import java.util.Iterator;
import java.security.InvalidKeyException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.service.IoConnector;
import org.apache.mina.transport.socket.nio.NioSocketConnector;
import org.apache.sshd.agent.ChannelAgentForwarding;
import org.apache.sshd.client.ClientFactoryManager;
import org.apache.sshd.client.ServerKeyVerifier;
import org.apache.sshd.client.SessionFactory;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.future.DefaultConnectFuture;
import org.apache.sshd.client.kex.DHG1;
import org.apache.sshd.client.kex.DHG14;
import org.apache.sshd.client.session.ClientSessionImpl;
import org.apache.sshd.common.*;
import org.apache.sshd.common.cipher.AES128CBC;
import org.apache.sshd.common.cipher.AES192CBC;
import org.apache.sshd.common.cipher.AES256CBC;
import org.apache.sshd.common.cipher.BlowfishCBC;
import org.apache.sshd.common.cipher.TripleDESCBC;
import org.apache.sshd.common.compression.CompressionNone;
import org.apache.sshd.common.mac.HMACMD5;
import org.apache.sshd.common.mac.HMACMD596;
import org.apache.sshd.common.mac.HMACSHA1;
import org.apache.sshd.common.mac.HMACSHA196;
import org.apache.sshd.common.random.BouncyCastleRandom;
import org.apache.sshd.common.random.JceRandom;
import org.apache.sshd.common.random.SingletonRandomFactory;
import org.apache.sshd.common.session.AbstractSession;
import org.apache.sshd.common.signature.SignatureDSA;
import org.apache.sshd.common.signature.SignatureRSA;
import org.apache.sshd.common.util.LogUtils;
import org.apache.sshd.common.util.NoCloseInputStream;
import org.apache.sshd.common.util.NoCloseOutputStream;
import org.apache.sshd.common.util.SecurityUtils;
import org.apache.sshd.client.PumpingMethod;

/**
 * Entry point for the client side of the SSH protocol.
 *
 * The default configured client can be created using
 * the {@link #setUpDefaultClient()}.  The next step is to
 * start the client using the {@link #start()} method.
 *
 * Sessions can then be created using on of the
 * {@link #connect(String, int)} or {@link #connect(java.net.SocketAddress)}
 * methods.
 *
 * The client can be stopped at anytime using the {@link #stop()} method.
 *
 * Following is an example of using the SshClient:
 * <pre>
 *    SshClient client = SshClient.setUpDefaultClient();
 *    client.start();
 *    try {
 *        ClientSession session = client.connect(host, port);
 *
 *        int ret = ClientSession.WAIT_AUTH;
 *        while ((ret & ClientSession.WAIT_AUTH) != 0) {
 *            System.out.print("Password:");
 *            BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
 *            String password = r.readLine();
 *            session.authPassword(login, password);
 *            ret = session.waitFor(ClientSession.WAIT_AUTH | ClientSession.CLOSED | ClientSession.AUTHED, 0);
 *        }
 *        if ((ret & ClientSession.CLOSED) != 0) {
 *            System.err.println("error");
 *            System.exit(-1);
 *        }
 *        ClientChannel channel = session.createChannel("shell");
 *        channel.setIn(new NoCloseInputStream(System.in));
 *        channel.setOut(new NoCloseOutputStream(System.out));
 *        channel.setErr(new NoCloseOutputStream(System.err));
 *        channel.open();
 *        channel.waitFor(ClientChannel.CLOSED, 0);
 *        session.close();
 *    } finally {
 *        client.stop();
 *    }
 * </pre>
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class SshClient extends AbstractFactoryManager implements ClientFactoryManager {
    private static final Log log = LogFactory.getLog(SshClient.class);
    protected IoConnector connector;
    protected SessionFactory sessionFactory;

    private ServerKeyVerifier serverKeyVerifier;

	private PumpingMethod pumpingMethod = PumpingMethod.OFF;
	private int nioProcessorCount = 0;
	private StreamPumperThread streamPumper = null;
	private long streamWaitTime = 100;

    public SshClient() {
    }

    public SessionFactory getSessionFactory() {
        return sessionFactory;
    }

    public void setSessionFactory(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    public ServerKeyVerifier getServerKeyVerifier() {
        return serverKeyVerifier;
    }

    public void setServerKeyVerifier(ServerKeyVerifier serverKeyVerifier) {
        this.serverKeyVerifier = serverKeyVerifier;
    }

    public void start() {
		if (nioProcessorCount <= 0)
			connector = new NioSocketConnector();
		else
			connector = new NioSocketConnector(nioProcessorCount);
        SessionFactory handler = sessionFactory;
        if (handler == null) {
            handler = new SessionFactory();
            sessionFactory = handler;
        }
        handler.setClient(this);
        connector.setHandler(handler);

        if (pumpingMethod == PumpingMethod.SELF) {
			streamPumper = new StreamPumperThread();
            log.trace("start StreamPumperThread");
			streamPumper.start();
		}
    }

    public void stop() {
        if(sessionFactory.getSessions().size()>0)
        {
            log.error("connector has sessions "+sessionFactory.getSessions());
        }
        connector.dispose();
        connector = null;
		if (streamPumper != null)
			streamPumper.close();
    }

    public ConnectFuture connect(String host, int port) throws Exception {
        assert host != null;
        assert port >= 0;
        if (connector == null) {
            throw new IllegalStateException("SshClient not started. Please call start() method before connecting to a server");
        }
        SocketAddress address = new InetSocketAddress(host, port);
        return connect(address);
    }

    public ConnectFuture connect(SocketAddress address) throws Exception {
        assert address != null;
        if (connector == null) {
            throw new IllegalStateException("SshClient not started. Please call start() method before connecting to a server");
        }
        final ConnectFuture connectFuture = new DefaultConnectFuture(null);
        connector.connect(address).addListener(new IoFutureListener<org.apache.mina.core.future.ConnectFuture>() {
            public void operationComplete(org.apache.mina.core.future.ConnectFuture future) {
                if (future.isCanceled()) {
                    connectFuture.cancel();
                } else if (future.getException() != null) {
                    connectFuture.setException(future.getException());
                } else {
                    ClientSessionImpl session = (ClientSessionImpl) AbstractSession.getSession(future.getSession());
                    connectFuture.setSession(session);
                }
            }
        });
        return connectFuture;
    }

    /**
     * Setup a default client.  The client does not require any additional setup.
     *
     * @return a newly create SSH client
     */
    public static SshClient setUpDefaultClient() {
        SshClient client = new SshClient();
        // DHG14 uses 2048 bits key which are not supported by the default JCE provider
        if (SecurityUtils.isBouncyCastleRegistered()) {
            client.setKeyExchangeFactories(Arrays.<NamedFactory<KeyExchange>>asList(
                    new DHG14.Factory(),
                    new DHG1.Factory()));
            client.setRandomFactory(new SingletonRandomFactory(new BouncyCastleRandom.Factory()));
        } else {
            client.setKeyExchangeFactories(Arrays.<NamedFactory<KeyExchange>>asList(
                    new DHG1.Factory()));
            client.setRandomFactory(new SingletonRandomFactory(new JceRandom.Factory()));
        }
        setUpDefaultCiphers(client);
        // Compression is not enabled by default
        // client.setCompressionFactories(Arrays.<NamedFactory<Compression>>asList(
        //         new CompressionNone.Factory(),
        //         new CompressionZlib.Factory(),
        //         new CompressionDelayedZlib.Factory()));
        client.setCompressionFactories(Arrays.<NamedFactory<Compression>>asList(
                new CompressionNone.Factory()));
        client.setMacFactories(Arrays.<NamedFactory<Mac>>asList(
                new HMACMD5.Factory(),
                new HMACSHA1.Factory(),
                new HMACMD596.Factory(),
                new HMACSHA196.Factory()));
        client.setSignatureFactories(Arrays.<NamedFactory<Signature>>asList(
                new SignatureDSA.Factory(),
                new SignatureRSA.Factory()));
        client.setChannelFactories(Arrays.<NamedFactory<Channel>>asList(
                new ChannelAgentForwarding.Factory()));
        return client;
    }

    private static void setUpDefaultCiphers(SshClient client) {
        List<NamedFactory<Cipher>> avail = new LinkedList<NamedFactory<Cipher>>();
        avail.add(new AES128CBC.Factory());
        avail.add(new TripleDESCBC.Factory());
        avail.add(new BlowfishCBC.Factory());
        avail.add(new AES192CBC.Factory());
        avail.add(new AES256CBC.Factory());

        for (Iterator<NamedFactory<Cipher>> i = avail.iterator(); i.hasNext();) {
            final NamedFactory<Cipher> f = i.next();
            try {
                final Cipher c = f.create();
                final byte[] key = new byte[c.getBlockSize()];
                final byte[] iv = new byte[c.getIVSize()];
                c.init(Cipher.Mode.Encrypt, key, iv);
            } catch (InvalidKeyException e) {
                i.remove();
            } catch (Exception e) {
                i.remove();
            }
        }
        client.setCipherFactories(avail);
    }

    /*=================================
          Main class implementation
     *=================================*/

    public static void main(String[] args) throws Exception {
        int port = 22;
        String host = null;
        String login = System.getProperty("user.name");
        boolean agentForward = false;
        List<String> command = null;
        int logLevel = 0;
        boolean error = false;

        for (int i = 0; i < args.length; i++) {
            if (command == null && "-p".equals(args[i])) {
                if (i + 1 >= args.length) {
                    System.err.println("option requires an argument: " + args[i]);
                    error = true;
                    break;
                }
                port = Integer.parseInt(args[++i]);
            } else if (command == null && "-l".equals(args[i])) {
                if (i + 1 >= args.length) {
                    System.err.println("option requires an argument: " + args[i]);
                    error = true;
                    break;
                }
                login = args[++i];
            } else if (command == null && "-v".equals(args[i])) {
                logLevel = 1;
            } else if (command == null && "-vv".equals(args[i])) {
                logLevel = 2;
            } else if (command == null && "-vvv".equals(args[i])) {
                logLevel = 3;
            } else if ("-A".equals(args[i])) {
                agentForward = true;
            } else if ("-a".equals(args[i])) {
                agentForward = false;
            } else if (command == null && args[i].startsWith("-")) {
                System.err.println("illegal option: " + args[i]);
                error = true;
                break;
            } else {
                if (host == null) {
                    host = args[i];
                } else {
                    if (command == null) {
                        command = new ArrayList<String>();
                    }
                    command.add(args[i]);
                }
            }
        }
        if (host == null) {
            System.err.println("hostname required");
            error = true;
        }
        if (error) {
            System.err.println("usage: ssh [-A|-a] [-v[v][v]] [-l login] [-p port] hostname [command]");
            System.exit(-1);
        }

        // TODO: handle log level

        SshClient client = SshClient.setUpDefaultClient();
        client.start();

        try {
            boolean hasKeys = false;

            /*
            String authSock = System.getenv(SshAgent.SSH_AUTHSOCKET_ENV_NAME);
            if (authSock == null) {
                KeyPair[] keys = null;
                AgentServer server = new AgentServer();
                authSock = server.start();
                List<String> files = new ArrayList<String>();
                File f = new File(System.getProperty("user.home"), ".ssh/id_dsa");
                if (f.exists() && f.isFile() && f.canRead()) {
                    files.add(f.getAbsolutePath());
                }
                f = new File(System.getProperty("user.home"), ".ssh/id_rsa");
                if (f.exists() && f.isFile() && f.canRead()) {
                    files.add(f.getAbsolutePath());
                }
                try {
                    if (files.size() > 0) {
                        keys = new FileKeyPairProvider(files.toArray(new String[0]), new PasswordFinder() {
                            public char[] getPassword() {
                                try {
                                    System.out.println("Enter password for private key: ");
                                    BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
                                    String password = r.readLine();
                                    return password.toCharArray();
                                } catch (IOException e) {
                                    return null;
                                }
                            }
                        }).loadKeys();
                    }
                } catch (Exception e) {
                }
                SshAgent agent = new AgentClient(authSock);
                for (KeyPair key : keys) {
                    agent.addIdentity(key, "");
                }
                agent.close();
            }
            if (authSock != null) {
                SshAgent agent = new AgentClient(authSock);
                hasKeys = agent.getIdentities().size() > 0;
            }
            client.getProperties().put(SshAgent.SSH_AUTHSOCKET_ENV_NAME, authSock);
            */

            ClientSession session = client.connect(host, port).await().getSession();
            int ret = ClientSession.WAIT_AUTH;

            while ((ret & ClientSession.WAIT_AUTH) != 0) {
                if (hasKeys) {
                    session.authAgent(login);
                    ret = session.waitFor(ClientSession.WAIT_AUTH | ClientSession.CLOSED | ClientSession.AUTHED, 0);
                } else {
                    System.out.print("Password:");
                    BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
                    String password = r.readLine();
                    session.authPassword(login, password);
                    ret = session.waitFor(ClientSession.WAIT_AUTH | ClientSession.CLOSED | ClientSession.AUTHED, 0);
                }
            }
            if ((ret & ClientSession.CLOSED) != 0) {
                System.err.println("error");
                System.exit(-1);
            }
            ClientChannel channel;
            if (command == null) {
                channel = session.createChannel(ClientChannel.CHANNEL_SHELL);
                ((ChannelShell) channel).setAgentForwarding(agentForward);
                channel.setIn(new NoCloseInputStream(System.in));
            } else {
                channel = session.createChannel(ClientChannel.CHANNEL_EXEC);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                Writer w = new OutputStreamWriter(baos);
                for (String cmd : command) {
                    w.append(cmd).append(" ");
                }
                w.append("\n");
                w.close();
                channel.setIn(new ByteArrayInputStream(baos.toByteArray()));
            }
            channel.setOut(new NoCloseOutputStream(System.out));
            channel.setErr(new NoCloseOutputStream(System.err));
            channel.open().await();
            channel.waitFor(ClientChannel.CLOSED, 0);
            session.close(false);
        } finally {
            client.stop();
        }
    }

	public void setNioProcessorCount(int nioProcessorCount) {
		this.nioProcessorCount = nioProcessorCount;
	}

	public int getNioProcessorCount() {
		return nioProcessorCount;
	}

	private class StreamPumperThread extends Thread {
		private boolean isClosed = false;

		public StreamPumperThread() {
            super("[SSHD] StreamPumperThread");
			log.trace("create stream pumping thread");
            setDaemon(true);
		}

		public synchronized void close() {
            log.trace("close StreamPumperThread");
			isClosed = true;
		}

		@Override
		public void run() {
			while (!isClosed && !isInterrupted()) {
				try {
					if (!pump()) {
						Thread.sleep(streamWaitTime);
					}
				} catch (InterruptedException e) {
                    log.trace("interrupt StreamPumperThread");
					close();
				}
                catch (Exception e)
                {
                    log.error("Unexpected Exception while pumping ",e);
                }
			}
		}
	}

	public PumpingMethod getPumpingMethod() {
		return pumpingMethod;
	}

	public void setPumpingMethod(PumpingMethod pumpingMethod) {
		if (connector != null)
			throw new IllegalArgumentException(
					"Set Pumping method before starting SshClient.");
		this.pumpingMethod = pumpingMethod;
	}

    public boolean pump()
    {
        log.trace("pump client");
        boolean dataTransferred = false;
        log.trace("try pump sessions");
        for (ClientSession session : sessionFactory.getSessions())
        {
            log.trace("check session pumping method");
            if (session.getPumpingMethod() == PumpingMethod.PARENT)
            {
                try
                {
                    dataTransferred = dataTransferred || session.pump();
                }
                catch (Exception e)
                {
                    log.error("Unexpected session pumping error",e);
                }
            }
        }
        LogUtils.trace(log, "client pump result {0}", dataTransferred);
        return dataTransferred;
    }

    public void setStreamWaitTime(long streamWaitTime) {
		this.streamWaitTime = streamWaitTime;
	}
}
