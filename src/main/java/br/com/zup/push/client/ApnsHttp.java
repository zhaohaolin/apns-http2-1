package br.com.zup.push.client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.concurrent.ExecutionException;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import br.com.zup.push.data.PushNotificationResponse;
import br.com.zup.push.data.ZupHttpPushNotification;
import br.com.zup.push.proxy.HttpProxyHandlerFactory;
import br.com.zup.push.proxy.ProxyConfig;
import br.com.zup.push.proxy.ProxyHandlerFactory;
import br.com.zup.push.proxy.Socks4ProxyHandlerFactory;
import br.com.zup.push.proxy.Socks5ProxyHandlerFactory;
import br.com.zup.push.util.P12Util;
import io.netty.util.concurrent.Future;

public class ApnsHttp {
    private static final Logger log = LoggerFactory.getLogger(ApnsHttp.class);

    private PushHttpClient<ZupHttpPushNotification> httpClient;
    private boolean sandboxEnvironment;

    public ApnsHttp(final File certificateFile, final String password) throws SSLException {
        try {
			this.httpClient = new PushHttpClient<>(certificateFile, password);
		} catch (IOException | KeyStoreException e) {
			throw new SSLException(e);
		}
        this.sandboxEnvironment = false;
    }
    
    public ApnsHttp(final InputStream p12InputStream, final String password) throws SSLException {
        try {
            KeyStore keyStore = P12Util.loadPCKS12KeyStore(p12InputStream, password);
            this.httpClient = new PushHttpClient<>(keyStore, password);
        } catch (SSLException e) {
            throw e;
        } catch (KeyStoreException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        this.sandboxEnvironment = false;
    }

    public PushNotificationResponse<ZupHttpPushNotification> pushMessageSync(final String message, final String token) throws ExecutionException, CertificateNotValidException {
        try {
        	if(!this.httpClient.isConnected()) {
        		stablishConnection();
        	}
            final ZupHttpPushNotification pushNotification;
            {
                pushNotification = new ZupHttpPushNotification(token, null, message);
            }
            final Future<PushNotificationResponse<ZupHttpPushNotification>> sendNotificationFuture =
                    this.httpClient.sendNotification(pushNotification);
            final PushNotificationResponse<ZupHttpPushNotification> pushNotificationResponse =
                    sendNotificationFuture.get();

            return pushNotificationResponse;
        } catch (final ExecutionException e) {
            log.info("Failed to send push notification.");
            e.printStackTrace();

            if (e.getCause() instanceof CertificateNotValidException){
                throw e;
            }
            if (e.getCause() instanceof ClientNotConnectedException){
                throw new CertificateNotValidException(e.getMessage());
            }
            throw e;
        } catch (InterruptedException e) {
            throw new ExecutionException(e);
        }
    }
    
    /**
     * Partially async, as it still need connection wait if doestn have connected before
     * @param message
     * @param token
     * @return
     * @throws ExecutionException
     */
    public Future<PushNotificationResponse<ZupHttpPushNotification>> pushMessageAsync(final String message, final String token) throws ExecutionException {
    		if(!this.httpClient.isConnected()) {
	            try {
	            	stablishConnection();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
    		}

            final ZupHttpPushNotification pushNotification;
            {
                pushNotification = new ZupHttpPushNotification(token, null, message);
            }
            final Future<PushNotificationResponse<ZupHttpPushNotification>> sendNotificationFuture =
                    this.httpClient.sendNotification(pushNotification);

        return sendNotificationFuture;
    }
    
    public ApnsHttp withProxy(ProxyConfig proxyConfig) {
    	final InetSocketAddress inetSocketAddress = new InetSocketAddress(proxyConfig.getAddress(), proxyConfig.getPort());
    	ProxyHandlerFactory proxyFactory = createProxyFactory(proxyConfig.getProtocol(), proxyConfig.getUsername().get(), proxyConfig.getPassword().get(), inetSocketAddress);
    	this.httpClient.setProxyHandlerFactory(proxyFactory);
        return this;
    }
    
    public ApnsHttp productionMode() {
        this.sandboxEnvironment = false;
        return this;
    }

    public ApnsHttp sandboxMode() {
        this.sandboxEnvironment = true;
        return this;
    }
    
    private ProxyHandlerFactory createProxyFactory(final String factoryProtocol, final String username, final String password, final SocketAddress socketAddress) {
    	if(factoryProtocol.equalsIgnoreCase(ProxyConfig.HTTP))
    		return new HttpProxyHandlerFactory(socketAddress, username, password);
    	if(factoryProtocol.equalsIgnoreCase(ProxyConfig.SOCKS_4))
    		return new Socks4ProxyHandlerFactory(socketAddress, username);
    	if(factoryProtocol.equalsIgnoreCase(ProxyConfig.SOCKS_5))
    		return new Socks5ProxyHandlerFactory(socketAddress, username, password);
		return null;
    }
    
    private void stablishConnection() throws InterruptedException {
    	final Future<Void> connectFuture = sandboxEnvironment ? this.httpClient.connectSandBox() : this.httpClient.connectProduction();
        connectFuture.await();
    }

    public void disconnect() {
        if (httpClient.isConnected()) {
            httpClient.disconnect();
        }
    }
}