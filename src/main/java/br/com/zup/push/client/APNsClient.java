package br.com.zup.push.client;

import io.netty.util.concurrent.Future;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.concurrent.ExecutionException;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import br.com.zup.push.data.PushNotification;
import br.com.zup.push.data.PushResponse;
import br.com.zup.push.data.DefaultPushNotification;
import br.com.zup.push.proxy.HttpProxyHandlerFactory;
import br.com.zup.push.proxy.ProxyConfig;
import br.com.zup.push.proxy.ProxyHandlerFactory;
import br.com.zup.push.proxy.Socks4ProxyHandlerFactory;
import br.com.zup.push.proxy.Socks5ProxyHandlerFactory;
import br.com.zup.push.util.P12Util;

public class APNsClient {
	
	private static final Logger					log	= LoggerFactory
															.getLogger(APNsClient.class);
	
	private APNsHttp2Client<PushNotification>	httpClient;
	private boolean								sandboxEnvironment;
	
	public APNsClient(final File certificateFile, final String password)
			throws SSLException {
		try {
			this.httpClient = new APNsHttp2Client<PushNotification>(
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
			this.httpClient = new APNsHttp2Client<PushNotification>(keyStore,
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
			CertificateNotValidException {
		try {
			if (!this.httpClient.isConnected()) {
				stablishConnection();
			}
			final PushNotification notification = new DefaultPushNotification(
					token, null, message);
			final Future<PushResponse<PushNotification>> future = this.httpClient
					.sendNotification(notification);
			final PushResponse<PushNotification> resp = future.get();
			
			return resp;
		} catch (final ExecutionException e) {
			log.error("Failed to send push notification.", e);
			// e.printStackTrace();
			
			if (e.getCause() instanceof CertificateNotValidException) {
				throw e;
			}
			if (e.getCause() instanceof ClientNotConnectedException) {
				throw new CertificateNotValidException(e.getMessage());
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
		if (!this.httpClient.isConnected()) {
			try {
				stablishConnection();
			} catch (InterruptedException e) {
				// e.printStackTrace();
				throw e;
			}
		}
		
		final DefaultPushNotification notify = new DefaultPushNotification(
				token, null, message);
		final Future<PushResponse<PushNotification>> future = this.httpClient
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
	
	private void stablishConnection() throws InterruptedException {
		final Future<Void> connectFuture = sandboxEnvironment ? this.httpClient
				.connectSandBox() : this.httpClient.connectProduction();
		connectFuture.await();
	}
	
	public void disconnect() {
		if (httpClient.isConnected()) {
			httpClient.disconnect();
		}
	}
}
