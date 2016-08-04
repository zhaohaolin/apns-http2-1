package br.com.zup.push.proxy;

import java.net.SocketAddress;

import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;

public class Socks4ProxyHandlerFactory implements ProxyHandlerFactory {

    private final SocketAddress proxyAddress;
    private final String username;

    public Socks4ProxyHandlerFactory(final SocketAddress proxyAddress) {
        this(proxyAddress, null);
    }

    public Socks4ProxyHandlerFactory(final SocketAddress proxyAddress, final String username) {
        this.proxyAddress = proxyAddress;
        this.username = username;
    }

    @Override
    public ProxyHandler createProxyHandler() {
        return new Socks4ProxyHandler(this.proxyAddress, this.username);
    }
}
