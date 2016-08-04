package br.com.zup.push.proxy;

import java.net.SocketAddress;

import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.ProxyHandler;

public class HttpProxyHandlerFactory implements ProxyHandlerFactory {

    private final SocketAddress proxyAddress;

    private final String username;

    public HttpProxyHandlerFactory(final SocketAddress proxyAddress) {
        this(proxyAddress, null, null);
    }

    private final String password;

    public HttpProxyHandlerFactory(final SocketAddress proxyAddress, final String username, final String password) {
        this.proxyAddress = proxyAddress;
        this.username = username;
        this.password = password;
    }

    @Override
    public ProxyHandler createProxyHandler() {
        final HttpProxyHandler handler;
        if (this.username != null && this.password != null) {
            handler = new HttpProxyHandler(this.proxyAddress, this.username, this.password);
        } else {
            handler = new HttpProxyHandler(this.proxyAddress);
        }

        return handler;
    }
}
