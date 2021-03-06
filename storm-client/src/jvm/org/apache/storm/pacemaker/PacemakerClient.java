/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.storm.pacemaker;

import org.apache.storm.Config;
import org.apache.storm.generated.HBMessage;
import org.apache.storm.messaging.netty.ISaslClient;
import org.apache.storm.messaging.netty.NettyRenameThreadFactory;
import org.apache.storm.security.auth.AuthUtils;
import org.apache.storm.utils.StormBoundedExponentialBackoffRetry;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.security.auth.login.Configuration;
import org.apache.storm.pacemaker.codec.ThriftNettyClientCodec;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PacemakerClient implements ISaslClient {

    private static final Logger LOG = LoggerFactory.getLogger(PacemakerClient.class);

    private String client_name;
    private String secret;
    private AtomicBoolean ready;
    private AtomicBoolean shutdown;
    private final ClientBootstrap bootstrap;
    private AtomicReference<Channel> channelRef;
    private InetSocketAddress remote_addr;
    private int maxPending = 100;
    private HBMessage messages[];
    private LinkedBlockingQueue<Integer> availableMessageSlots;
    private ThriftNettyClientCodec.AuthMethod authMethod;

    private static Timer timer = new Timer(true);

    private StormBoundedExponentialBackoffRetry backoff = new StormBoundedExponentialBackoffRetry(100, 5000, 20);
    private int retryTimes = 0;

    //the constructor is invoked by pacemaker-state-factory-test
    public PacemakerClient() {
        bootstrap = new ClientBootstrap();
    }
    public PacemakerClient(Map<String, Object> config, String host) {

        int port = (int)config.get(Config.PACEMAKER_PORT);
        client_name = (String)config.get(Config.TOPOLOGY_NAME);
        if(client_name == null) {
            client_name = "pacemaker-client";
        }

        String auth = (String)config.get(Config.PACEMAKER_AUTH_METHOD);
        ThriftNettyClientCodec.AuthMethod authMethod;

        switch(auth) {

        case "DIGEST":
            Configuration login_conf = AuthUtils.GetConfiguration(config);
            authMethod = ThriftNettyClientCodec.AuthMethod.DIGEST;
            secret = AuthUtils.makeDigestPayload(login_conf, AuthUtils.LOGIN_CONTEXT_PACEMAKER_DIGEST);
            if(secret == null) {
                LOG.error("Can't start pacemaker server without digest secret.");
                throw new RuntimeException("Can't start pacemaker server without digest secret.");
            }
            break;

        case "KERBEROS":
            authMethod = ThriftNettyClientCodec.AuthMethod.KERBEROS;
            break;

        case "NONE":
            authMethod = ThriftNettyClientCodec.AuthMethod.NONE;
            break;

        default:
            authMethod = ThriftNettyClientCodec.AuthMethod.NONE;
            LOG.warn("Invalid auth scheme: '{}'. Falling back to 'NONE'", auth);
            break;
        }

        ready = new AtomicBoolean(false);
        shutdown = new AtomicBoolean(false);
        channelRef = new AtomicReference<Channel>(null);
        setupMessaging();

        ThreadFactory bossFactory = new NettyRenameThreadFactory("client-boss");
        ThreadFactory workerFactory = new NettyRenameThreadFactory("client-worker");
        NioClientSocketChannelFactory factory =
            new NioClientSocketChannelFactory(Executors.newCachedThreadPool(bossFactory),
                                              Executors.newCachedThreadPool(workerFactory));
        bootstrap = new ClientBootstrap(factory);
        bootstrap.setOption("tcpNoDelay", true);
        bootstrap.setOption("sendBufferSize", 5242880);
        bootstrap.setOption("keepAlive", true);

        remote_addr = new InetSocketAddress(host, port);
        int thriftMessageMaxSize = (Integer) config.get(Config.PACEMAKER_THRIFT_MESSAGE_SIZE_MAX);
        ChannelPipelineFactory pipelineFactory =
            new ThriftNettyClientCodec(this, config, authMethod, host, thriftMessageMaxSize)
            .pipelineFactory();
        bootstrap.setPipelineFactory(pipelineFactory);
        bootstrap.connect(remote_addr);
    }

    private void setupMessaging() {
        messages = new HBMessage[maxPending];
        availableMessageSlots = new LinkedBlockingQueue<Integer>();
        for(int i = 0; i < maxPending; i++) {
            availableMessageSlots.add(i);
        }
    }

    @Override
    public synchronized void channelConnected(Channel channel) {
        Channel oldChannel = channelRef.get();
        if (oldChannel != null) {
            LOG.debug("Closing oldChannel is connected: {}", oldChannel.toString());
            close_channel();
        }

        LOG.debug("Channel is connected: {}", channel.toString());
        channelRef.set(channel);

        //If we're not going to authenticate, we can begin sending.
        if(authMethod == ThriftNettyClientCodec.AuthMethod.NONE) {
            ready.set(true);
            this.notifyAll();
        }
        retryTimes = 0;
    }

    @Override
    public synchronized void channelReady() {
        LOG.debug("Channel is ready.");
        ready.set(true);
        this.notifyAll();
    }

    public String name() {
        return client_name;
    }

    public String secretKey() {
        return secret;
    }

    public HBMessage send(HBMessage m) throws InterruptedException {
        LOG.debug("Sending message: {}", m.toString());

        int next = availableMessageSlots.take();
        synchronized (m) {
            m.set_message_id(next);
            messages[next] = m;
            LOG.debug("Put message in slot: {}", Integer.toString(next));
            do {
                try {
                    waitUntilReady();
                    Channel channel = channelRef.get();
                    if (channel != null) {
                        channel.write(m);
                        m.wait(1000);
                    }
                } catch (PacemakerConnectionException exp) {
                    LOG.error("error attempting to write to a channel {}", exp);
                }
            } while (messages[next] == m);
        }

        HBMessage ret = messages[next];
        if(ret == null) {
            // This can happen if we lost the connection and subsequently reconnected or timed out.
            send(m);
        }
        messages[next] = null;
        LOG.debug("Got Response: {}", ret);
        return ret;

    }

    private void waitUntilReady() throws PacemakerConnectionException, InterruptedException {
        // Wait for 'ready' (channel connected and maybe authentication)
        if(!ready.get() || channelRef.get() == null) {
            synchronized(this) {
                if(!ready.get()) {
                    LOG.debug("Waiting for netty channel to be ready.");
                    this.wait(1000);
                    if(!ready.get() || channelRef.get() == null) {
                        throw new PacemakerConnectionException("Timed out waiting for channel ready.");
                    }
                }
            }
        }
    }

    public void gotMessage(HBMessage m) {
        int message_id = m.get_message_id();
        if(message_id >=0 && message_id < maxPending) {

            LOG.debug("Pacemaker client got message: {}", m.toString());
            HBMessage request = messages[message_id];

            if(request == null) {
                LOG.debug("No message for slot: {}", Integer.toString(message_id));
            }
            else {
                synchronized(request) {
                    messages[message_id] = m;
                    request.notifyAll();
                    availableMessageSlots.add(message_id);
                }
            }
        }
        else {
            LOG.error("Got Message with bad id: {}", m.toString());
        }
    }

    public void reconnect() {
        final PacemakerClient client = this;
        timer.schedule(new TimerTask() {
                public void run() {
                    client.doReconnect();
                }
            },
            backoff.getSleepTimeMs(retryTimes++, 0));
        ready.set(false);
        setupMessaging();
    }

    public synchronized void doReconnect() {
        close_channel();
        if (!shutdown.get()) {
            bootstrap.connect(remote_addr);
        }
    }

    public void shutdown() {
        shutdown.set(true);
        bootstrap.releaseExternalResources();
    }

    private synchronized void close_channel() {
        if (channelRef.get() != null) {
            channelRef.get().close();
            LOG.debug("channel {} closed", remote_addr);
            channelRef.set(null);
        }
    }

    public void close() {
        close_channel();
    }
}
