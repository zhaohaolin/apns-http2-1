package br.com.zup.push.proxy;

import java.net.SocketAddress;

import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;

public class Socks5ProxyHandlerFactory implements ProxyHandlerFactory {

    private final SocketAddress proxyAddress;

    private final String username;
    private final String password;

    public Socks5ProxyHandlerFactory(final SocketAddress proxyAddress) {
        this(proxyAddress, null, null);
    }

    public Socks5ProxyHandlerFactory(final SocketAddress proxyAddress, final String username, final String password) {
        this.proxyAddress = proxyAddress;

        this.username = username;
        this.password = password;
    }

    @Override
    public ProxyHandler createProxyHandler() {
        return new Socks5ProxyHandler(this.proxyAddress, this.username, this.password);
    }
}
