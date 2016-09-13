package br.com.zup.push;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.concurrent.ExecutionException;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import br.com.zup.push.client.Http2Client;
import br.com.zup.push.data.APNsCallBack;
import br.com.zup.push.data.DefaultPushNotification;
import br.com.zup.push.util.P12Util;

public class APNsClient {
	
	private static final Logger	LOG	= LoggerFactory.getLogger(APNsClient.class);
	
	private final Http2Client	http2Client;
	
	public APNsClient(final File certificateFile, final String password,
			final APNsCallBack callback, boolean sandboxEnvironment,
			int maxSession) throws SSLException {
		try {
			this.http2Client = new Http2Client(certificateFile, password,
					callback, sandboxEnvironment, maxSession);
		} catch (IOException | KeyStoreException e) {
			throw new SSLException(e);
		}
	}
	
	public APNsClient(final InputStream p12InputStream, final String password,
			final APNsCallBack callback, boolean sandboxEnvironment,
			int maxSession) throws KeyStoreException, IOException {
		try {
			KeyStore keyStore = P12Util.loadPCKS12KeyStore(p12InputStream,
					password);
			this.http2Client = new Http2Client(keyStore, password, callback,
					sandboxEnvironment, maxSession);
		} catch (SSLException e) {
			throw e;
		} catch (KeyStoreException e) {
			// e.printStackTrace();
			throw e;
		} catch (IOException e) {
			// e.printStackTrace();
			throw e;
		}
	}
	
	public final synchronized void start() {
		http2Client.start();
	}
	
	public final synchronized void stop() {
		http2Client.stop();
	}
	
	/**
	 * Partially async, as it still need connection wait if doestn have
	 * connected before
	 * @param message
	 * @param token
	 * @return
	 * @throws ExecutionException
	 * @throws InterruptedException
	 */
	public void send(final String message, final String token)
			throws ExecutionException, InterruptedException {
		
		final DefaultPushNotification notify = new DefaultPushNotification(
				token, null, message);
		this.http2Client.sendNotification(notify);
		return;
	}
	
	// public ApnsHttp withProxy(ProxyConfig proxyConfig) {
	// final InetSocketAddress inetSocketAddress = new
	// InetSocketAddress(proxyConfig.getAddress(), proxyConfig.getPort());
	// ProxyHandlerFactory proxyFactory =
	// createProxyFactory(proxyConfig.getProtocol(),
	// proxyConfig.getUsername().get(), proxyConfig.getPassword().get(),
	// inetSocketAddress);
	// this.httpClient.setProxyHandlerFactory(proxyFactory);
	// return this;
	// }
	
	// private ProxyHandlerFactory createProxyFactory(
	// final String factoryProtocol, final String username,
	// final String password, final SocketAddress socketAddress) {
	// if (factoryProtocol.equalsIgnoreCase(ProxyConfig.HTTP))
	// return new HttpProxyHandlerFactory(socketAddress, username,
	// password);
	// if (factoryProtocol.equalsIgnoreCase(ProxyConfig.SOCKS_4))
	// return new Socks4ProxyHandlerFactory(socketAddress, username);
	// if (factoryProtocol.equalsIgnoreCase(ProxyConfig.SOCKS_5))
	// return new Socks5ProxyHandlerFactory(socketAddress, username,
	// password);
	// return null;
	// }
	
}
