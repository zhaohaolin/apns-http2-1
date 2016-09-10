package br.com.zup.push.client;

import io.netty.util.concurrent.Future;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import br.com.zup.push.data.DefaultPushNotification;
import br.com.zup.push.data.PushNotification;
import br.com.zup.push.data.PushResponse;
import br.com.zup.push.util.P12Util;

public class APNsClient {
	
	private static final Logger				LOG	= LoggerFactory
														.getLogger(APNsClient.class);
	
	private Http2Client<PushNotification>	http2Client;
	private boolean							sandboxEnvironment;
	
	public APNsClient(final File certificateFile, final String password)
			throws SSLException {
		try {
			this.http2Client = new Http2Client<PushNotification>(
					certificateFile, password);
		} catch (IOException | KeyStoreException e) {
			throw new SSLException(e);
		}
		this.sandboxEnvironment = false;
	}
	
	public APNsClient(final InputStream p12InputStream, final String password)
			throws KeyStoreException, IOException {
		try {
			KeyStore keyStore = P12Util.loadPCKS12KeyStore(p12InputStream,
					password);
			this.http2Client = new Http2Client<PushNotification>(keyStore,
					password);
		} catch (SSLException e) {
			throw e;
		} catch (KeyStoreException e) {
			// e.printStackTrace();
			throw e;
		} catch (IOException e) {
			// e.printStackTrace();
			throw e;
		}
		this.sandboxEnvironment = false;
	}
	
	public PushResponse<PushNotification> pushMessageSync(final String message,
			final String token) throws ExecutionException,
			CertificateNotValidException, TimeoutException {
		try {
			if (!this.http2Client.isConnected()) {
				stablishConnection();
			}
			final PushNotification notification = new DefaultPushNotification(
					token, null, message);
			final Future<PushResponse<PushNotification>> future = this.http2Client
					.sendNotification(notification);
			// final PushResponse<PushNotification> resp = future.get();
			final PushResponse<PushNotification> resp = future.get(1,
					TimeUnit.SECONDS);
			return resp;
		} catch (final ExecutionException e) {
			LOG.error("Failed to send push notification.", e);
			
			if (e.getCause() instanceof CertificateNotValidException) {
				throw e;
			}
			if (e.getCause() instanceof ClientNotConnectedException) {
				throw new ClientNotConnectedException(e.getMessage());
			}
			throw e;
		} catch (InterruptedException e) {
			throw new ExecutionException(e);
		}
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
	public Future<PushResponse<PushNotification>> pushMessageAsync(
			final String message, final String token)
			throws ExecutionException, InterruptedException {
		if (!this.http2Client.isConnected()) {
			try {
				stablishConnection();
			} catch (InterruptedException e) {
				// e.printStackTrace();
				throw e;
			}
		}
		
		final DefaultPushNotification notify = new DefaultPushNotification(
				token, null, message);
		final Future<PushResponse<PushNotification>> future = this.http2Client
				.sendNotification(notify);
		
		return future;
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
	
	public APNsClient productionMode() {
		this.sandboxEnvironment = false;
		return this;
	}
	
	public APNsClient sandboxMode() {
		this.sandboxEnvironment = true;
		return this;
	}
	
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
	
	int	times	= 0;
	
	public synchronized void stablishConnection() throws InterruptedException {
		try {
			final Future<Void> connectFuture = sandboxEnvironment ? this.http2Client
					.connectSandBox() : this.http2Client.connectProduction();
			// connectFuture.await();
			connectFuture.await(10, TimeUnit.SECONDS);
			return;
		} catch (Exception e) {
			LOG.error("stablishConnection failure: ", e);
			// re connect
			times++;
			if (times < 10) {
				stablishConnection();
				return;
			}
			
			throw e;
		} finally {
			times = 0;
		}
	}
	
	public void disconnect() {
		if (http2Client.isConnected()) {
			http2Client.disconnect();
		}
	}
}
