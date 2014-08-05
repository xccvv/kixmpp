package com.kixeye.kixmpp.client;

/*
 * #%L
 * KIXMPP
 * %%
 * Copyright (C) 2014 KIXEYE, Inc
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */


import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.jdom2.Attribute;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.output.XMLOutputter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.kixeye.kixmpp.KixmppAuthException;
import com.kixeye.kixmpp.KixmppCodec;
import com.kixeye.kixmpp.KixmppException;
import com.kixeye.kixmpp.KixmppStanzaRejectedException;
import com.kixeye.kixmpp.client.module.KixmppClientModule;
import com.kixeye.kixmpp.client.module.muc.MucKixmppClientModule;
import com.kixeye.kixmpp.client.module.presence.PresenceKixmppClientModule;
import com.kixeye.kixmpp.handler.KixmppEventEngine;
import com.kixeye.kixmpp.handler.KixmppStanzaHandler;
import com.kixeye.kixmpp.interceptor.KixmppStanzaInterceptor;

/**
 * A XMPP client.
 * 
 * @author ebahtijaragic
 */
public class KixmppClient implements AutoCloseable {
	private static final Logger logger = LoggerFactory.getLogger(KixmppClient.class);
	
    private final ConcurrentHashMap<KixmppClientOption<?>, Object> clientOptions = new ConcurrentHashMap<KixmppClientOption<?>, Object>();
	private final Bootstrap bootstrap;
	
	private final KixmppEventEngine eventEngine;
	
	private final SslContext sslContext;
	
	private final Set<KixmppStanzaInterceptor> interceptors = Collections.newSetFromMap(new ConcurrentHashMap<KixmppStanzaInterceptor, Boolean>());

	private final Set<String> modulesToRegister = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
	private final ConcurrentHashMap<String, KixmppClientModule> modules = new ConcurrentHashMap<>();
	
	private SettableFuture<KixmppClient> deferredLogin;
	private SettableFuture<KixmppClient> deferredDisconnect;
	
	private String username;
	private String password;
	private String resource;
	private String domain;
	private String jid;
	
	private AtomicReference<Channel> channel = new AtomicReference<>(null);
	
	private AtomicReference<State> state = new AtomicReference<>(State.DISCONNECTED);
	private static enum State {
		CONNECTING,
		CONNECTED,
		
		LOGGING_IN,
		SECURING,
		LOGGED_IN,
		
		DISCONNECTING,
		DISCONNECTED
	}
	
	/**
	 * Creates a new {@link KixmppClient} with the given ssl engine.
	 * 
	 * @param sslContext
	 */
	public KixmppClient(SslContext sslContext) {
		this(new NioEventLoopGroup(), new KixmppEventEngine(), sslContext);
	}

	/**
	 * Creates a new {@link KixmppClient}.
	 * 
	 * @param eventLoopGroup
	 * @param environment
	 * @param reactor
	 * @param sslContext
	 */
	public KixmppClient(EventLoopGroup eventLoopGroup, KixmppEventEngine eventEngine, SslContext sslContext) {
		assert sslContext.isClient() : "The given SslContext must be a client context.";
		
		bootstrap = new Bootstrap()
			.group(eventLoopGroup)
			.channel(NioSocketChannel.class)
			.option(ChannelOption.TCP_NODELAY, false)
			.option(ChannelOption.SO_KEEPALIVE, true)
			.handler(new KixmppClientChannelInitializer());
		
		this.sslContext = sslContext;
		this.eventEngine = eventEngine;
		
		// set modules to be registered
		this.modulesToRegister.add(MucKixmppClientModule.class.getName());
		this.modulesToRegister.add(PresenceKixmppClientModule.class.getName());
	}
	
	/**
	 * Connects to the hostname and port.
	 * 
	 * @param hostname
	 * @param port
	 */
	public ListenableFuture<KixmppClient> connect(String hostname, int port, String domain) {
		checkAndSetState(State.CONNECTING, State.DISCONNECTED);
		
		this.domain = domain;
		
		setUp();
		
		final SettableFuture<KixmppClient> responseFuture = SettableFuture.create();
		
		bootstrap.connect(hostname, port).addListener(new GenericFutureListener<Future<? super Void>>() {
			public void operationComplete(Future<? super Void> future) throws Exception {
				if (future.isSuccess()) {
					if (state.compareAndSet(State.CONNECTING, State.CONNECTED)) {
						channel.set(((ChannelFuture)future).channel());
						responseFuture.set(KixmppClient.this);
					}
				} else {
					state.set(State.DISCONNECTED);
					responseFuture.setException(future.cause());
				}
			}
		});
		
		return responseFuture;
	}
	
	/**
	 * Logs the user into the XMPP server.
	 * 
	 * @param username
	 * @param password
	 * @param resource
	 * @throws InterruptedException 
	 */
	public ListenableFuture<KixmppClient> login(String username, String password, String resource) throws InterruptedException {
		checkAndSetState(State.LOGGING_IN, State.CONNECTED);
		
		this.username = username;
		this.password = password;
		this.resource = resource;

		deferredLogin = SettableFuture.create();
		
		KixmppCodec.sendXmppStreamRootStart(channel.get(), null, domain);
		
		return deferredLogin;
	}
	
	/**
	 * Disconnects from the current server.
	 */ 
	public ListenableFuture<KixmppClient> disconnect() {
		if (state.get() == State.DISCONNECTED) {
			final SettableFuture<KixmppClient> deferred = SettableFuture.create();
			deferred.set(this);
			
			return deferred;
		} else if (state.get() == State.DISCONNECTING) {
			return deferredDisconnect;
		}
		
		checkAndSetState(State.DISCONNECTING, State.CONNECTED, State.LOGGED_IN, State.LOGGING_IN);

		deferredDisconnect = SettableFuture.create();
		
		cleanUp();
		
		final Channel currentChannel = channel.get();

		if (currentChannel != null) {
			KixmppCodec.sendXmppStreamRootStop(channel.get()).addListener(new GenericFutureListener<Future<? super Void>>() {
				public void operationComplete(Future<? super Void> arg0) throws Exception {
					currentChannel.close().addListener(new GenericFutureListener<Future<? super Void>>() {
						public void operationComplete(Future<? super Void> arg0) throws Exception {
							deferredDisconnect.set(KixmppClient.this);
							state.set(State.DISCONNECTED);
						}
					});
				}
			});
		} else {
			deferredDisconnect.setException(new KixmppException("No channel available to close."));
		}

		return deferredDisconnect;
	}

	/**
	 * @see java.lang.AutoCloseable#close()
	 */
	public void close() throws Exception {
		disconnect();
	}
	
	/**
	 * Sets the client's {@link KixmppClientOption}s.
	 * 
	 * @param option
	 * @param value
	 * @return
	 */
    public <T> KixmppClient clientOption(KixmppClientOption<T> option, T value) {
    	if (value == null) {
    		clientOptions.remove(option);
    	} else {
    		clientOptions.put(option, value);
    	}
    	return this;
    }

	/**
	 * Sets Netty {@link ChannelOption}s.
	 * 
	 * @param option
	 * @param value
	 * @return
	 */
    public <T> KixmppClient channelOption(ChannelOption<T> option, T value) {
    	bootstrap.option(option, value);
    	return this;
    }
    
    /**
     * Adds a stanza interceptor.
     * 
     * @param interceptor
     */
    public boolean addInterceptor(KixmppStanzaInterceptor interceptor) {
    	return interceptors.add(interceptor);
    }
    
    /**
     * Removes a stanza interceptor.
     * 
     * @param interceptor
     */
    public boolean removeInterceptor(KixmppStanzaInterceptor interceptor) {
    	return interceptors.remove(interceptor);
    }
    
    /**
     * Gets the event engine.
     * 
     * @return
     */
    public KixmppEventEngine getEventEngine() {
    	return eventEngine;
    }
    
    /**
     * @param moduleClass
     * @return true if module is installed
     */
    public boolean hasActiveModule(Class<?> moduleClass) {
    	return modules.containsKey(moduleClass.getName());
    }
    
    /**
     * Gets or installs a module.
     * 
     * @param moduleClass
     * @return
     */
    @SuppressWarnings("unchecked")
	public <T extends KixmppClientModule> T module(Class<T> moduleClass) {
    	if (!(state.get() == State.CONNECTED || state.get() == State.LOGGED_IN)) {
			throw new IllegalStateException(String.format("The current state is [%s] but must be [CONNECTED or LOGGED_IN]", state.get()));
    	}
    	
    	T module = (T)modules.get(moduleClass.getName());
    	
    	if (module == null) {
    		module = (T)installModule(moduleClass.getName());
    	}

    	return module;
    }
    
    /**
     * Writes a stanza to the channel.
     * 
     * @param element
     */
    public void sendStanza(Element element) {
    	channel.get().writeAndFlush(element);
    }
    
    /**
     * Gets the jid of the user.
     * 
     * @return
     */
    public String getJid() {
    	if (jid != null) {
    		return jid;
    	} else {
    		return username + "@" + domain + "/" + resource;
    	}
    }
    
    /**
     * Checks the state and sets it.
     * 
     * @param update
     * @param expectedStates
     * @throws IllegalStateException
     */
    private void checkAndSetState(State update, State... expectedStates) throws IllegalStateException {
    	if (expectedStates != null) {
    		boolean wasSet = false;
    		
    		for (State expectedState : expectedStates) {
    			if (state.compareAndSet(expectedState, update)) {
    				wasSet = true;
    				break;
    			}
    		}
    		
    		if (!wasSet) {
    			throw new IllegalStateException(String.format("The current state is [%s] but must be [%s]", state.get(), expectedStates));
    		}
    	} else {
    		if (!state.compareAndSet(null, update)) {
    			throw new IllegalStateException(String.format("The current state is [%s] but must be [null]", state.get()));
			}
    	}
    }
    
    /**
     * Registers all the consumers and modules.
     */
    private void setUp() {
    	if (state.get() == State.CONNECTING) {
    		// this client deals with the following stanzas
    		eventEngine.registerGlobalStanzaHandler("stream:features", streamFeaturesHandler);

    		eventEngine.registerGlobalStanzaHandler("proceed", tlsResponseHandler);
    		
    		eventEngine.registerGlobalStanzaHandler("success", authResultHandler);
    		eventEngine.registerGlobalStanzaHandler("failure", authResultHandler);

    		eventEngine.registerGlobalStanzaHandler("iq", iqResultHandler);
    		
    		// register all modules
    		for (String moduleClassName : modulesToRegister) {
    			installModule(moduleClassName);
    		}
    	}
    }
    
    /**
     * Unregisters all consumers and modules.
     */
    private void cleanUp() {
    	if (state.get() == State.DISCONNECTING) {
    		for (Entry<String, KixmppClientModule> entry : modules.entrySet()) {
    			entry.getValue().uninstall(this);
    		}
    		
    		eventEngine.unregisterAll();
    	}
    }
    
    /**
     * Tries to install module.
     * 
     * @param moduleClassName
     * @throws Exception
     */
    private KixmppClientModule installModule(String moduleClassName) {
    	KixmppClientModule module = null;
		
		try {
			module = (KixmppClientModule)Class.forName(moduleClassName).newInstance();
			module.install(this);
			
			modules.put(moduleClassName, module);
		} catch (Exception e) {
			logger.error("Error while installing module", e);
		}
		
		return module;
    }
    
    /**
     * Performs auth.
     */
    private void performAuth() {
    	byte[] authToken = ("\0" + username + "\0" + password).getBytes(StandardCharsets.UTF_8);
		
		Element auth = new Element("auth", "urn:ietf:params:xml:ns:xmpp-sasl");
		auth.setAttribute("mechanism", "PLAIN");
		
		ByteBuf rawCredentials = channel.get().alloc().buffer().writeBytes(authToken);
		ByteBuf encodedCredentials = Base64.encode(rawCredentials);
		String encodedCredentialsString = encodedCredentials.toString(StandardCharsets.UTF_8);
		encodedCredentials.release();
		rawCredentials.release();
		
		auth.setText(encodedCredentialsString);
		
		channel.get().writeAndFlush(auth);
    }
    
    /**
     * Handles stream features
     */
    private final KixmppStanzaHandler streamFeaturesHandler = new KixmppStanzaHandler() {
		public void handle(Channel channel, Element streamFeatures) {
			Element startTls = streamFeatures.getChild("starttls", Namespace.getNamespace("urn:ietf:params:xml:ns:xmpp-tls"));
			
			Object enableTls = clientOptions.get(KixmppClientOption.ENABLE_TLS);
			
			if ((enableTls != null && (boolean)enableTls) || (startTls != null && startTls.getChild("required", startTls.getNamespace()) != null)) {
				// if its required, always do tls
				startTls = new Element("starttls", "tls", "urn:ietf:params:xml:ns:xmpp-tls");
				
				KixmppClient.this.channel.get().writeAndFlush(startTls);
			} else {
				performAuth();
			}
		}
	};
	
	/**
     * Handles stream features
     */
    private final KixmppStanzaHandler tlsResponseHandler = new KixmppStanzaHandler() {
		public void handle(Channel channel, Element tlsResponse) {
			if (state.compareAndSet(State.LOGGING_IN, State.SECURING)) {
				SslHandler handler = sslContext.newHandler(KixmppClient.this.channel.get().alloc());
				handler.handshakeFuture().addListener(new GenericFutureListener<Future<? super Channel>>() {
					public void operationComplete(Future<? super Channel> future) throws Exception {
						if (future.isSuccess()) {
							KixmppClient.this.channel.get().pipeline().replace(KixmppCodec.class, "kixmppCodec", new KixmppCodec());
							
							KixmppCodec.sendXmppStreamRootStart(KixmppClient.this.channel.get(), null, domain);
						} else {
							deferredLogin.setException(new KixmppAuthException("tls failed"));
						}
					}
				});
				
				KixmppClient.this.channel.get().pipeline().addFirst("sslHandler", handler);
			}
		}
	};
	
	/**
     * Handles auth success
     */
    private final KixmppStanzaHandler authResultHandler = new KixmppStanzaHandler() {
		public void handle(Channel channel, Element authResult) {
			switch (authResult.getName()) {
				case "success":
					// send bind
					Element bindRequest = new Element("iq");
					bindRequest.setAttribute("type", "set");
					bindRequest.setAttribute("id", "bind");
					
					Element bind = new Element("bind", "urn:ietf:params:xml:ns:xmpp-bind");
					
					if (KixmppClient.this.resource != null) {
						Element resource = new Element("resource", null, "urn:ietf:params:xml:ns:xmpp-bind");
						resource.setText(KixmppClient.this.resource);
						bind.addContent(resource);
					}
					
					bindRequest.addContent(bind);
		
					KixmppClient.this.channel.get().writeAndFlush(bindRequest);
					break;
				default:
					// fail
					deferredLogin.setException(new KixmppAuthException(new XMLOutputter().outputString(authResult)));
					break;
			}
		}
	};
	
	/**
     * Handles iq stanzas
     */
    private final KixmppStanzaHandler iqResultHandler = new KixmppStanzaHandler() {
		public void handle(Channel channel, Element iqResult) {
			Attribute idAttribute = iqResult.getAttribute("id");
			Attribute typeAttribute = iqResult.getAttribute("type");
			
			if (idAttribute != null) {
				switch (idAttribute.getValue()) {
					case "bind":
						if (typeAttribute != null && "result".equals(typeAttribute.getValue())) {
							Element bind = iqResult.getChild("bind", Namespace.getNamespace("urn:ietf:params:xml:ns:xmpp-bind"));
							
							if (bind != null) {
								jid = bind.getChildText("jid", bind.getNamespace());
							}

							// start the session
							Element startSession = new Element("iq");
							startSession.setAttribute("to", domain);
							startSession.setAttribute("type", "set");
							startSession.setAttribute("id", "session");
							
							Element session = new Element("session", "urn:ietf:params:xml:ns:xmpp-session");
							startSession.addContent(session);

							KixmppClient.this.channel.get().writeAndFlush(startSession);
						} else {
							// fail
							deferredLogin.setException(new KixmppAuthException(new XMLOutputter().outputString(iqResult)));
						}
						break;
					case "session":
						if (typeAttribute != null && "result".equals(typeAttribute.getValue())) {
							deferredLogin.set(KixmppClient.this);
							state.set(State.LOGGED_IN);
							
							logger.debug("Logged in as: " + jid);
						} else {
							// fail
							deferredLogin.setException(new KixmppAuthException(new XMLOutputter().outputString(iqResult)));
						}

						// no need to keep reference around
						deferredLogin = null;
						break;
					default:
						logger.warn("Unsupported IQ stanza: " + new XMLOutputter().outputString(iqResult));
						break;
				}
			}
		}
	};
	
    /**
     * Channel initializer for the {@link KixmppClient}.
     * 
     * @author ebahtijaragic
     */
	private final class KixmppClientChannelInitializer extends ChannelInitializer<SocketChannel> {
		/**
		 * @see io.netty.channel.ChannelInitializer#initChannel(io.netty.channel.Channel)
		 */
		protected void initChannel(SocketChannel ch) throws Exception {
			// initially only add the codec and out client handler
			ch.pipeline().addLast("kixmppCodec", new KixmppCodec());
			ch.pipeline().addLast("kixmppClientMessageHandler", new KixmppClientMessageHandler());
		}
	}
	
	/**
	 * Message handler for the {@link KixmppClient}
	 * 
	 * @author ebahtijaragic
	 */
	private final class KixmppClientMessageHandler extends ChannelDuplexHandler {
		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
			if (msg instanceof Element) {
				Element stanza = (Element)msg;
				
				boolean rejected = false;
				
				for (KixmppStanzaInterceptor interceptor : interceptors) {
					try {
						interceptor.interceptIncoming(ctx.channel(),(Element)msg);
					} catch (KixmppStanzaRejectedException e) {
						rejected = true;
						
						logger.debug("Incoming stanza interceptor [{}] threw an rejected exception.", interceptor, e);
					} catch (Exception e) {
						logger.error("Incoming stanza interceptor [{}] threw an exception.", interceptor, e);
					}
				}
	
				if (!rejected) {
					eventEngine.publishStanza(ctx.channel(), stanza);
				}
			}
		}
		
		@Override
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
			boolean rejected = false;
			
			if (msg instanceof Element) {
				for (KixmppStanzaInterceptor interceptor : interceptors) {
					try {
						interceptor.interceptOutgoing(ctx.channel(), (Element)msg);
					} catch (KixmppStanzaRejectedException e) {
						rejected = true;
						
						logger.debug("Outgoing stanza interceptor [{}] threw an rejected exception.", interceptor, e);
					} catch (Exception e) {
						logger.error("Outgoing stanza interceptor [{}] threw an exception.", interceptor, e);
					}
				}
			}
			
			if (!rejected) {
				super.write(ctx, msg, promise);
			}
		}
		
		@Override
		public void channelInactive(ChannelHandlerContext ctx) throws Exception {
			KixmppClient.this.disconnect();
		}
	}
}
