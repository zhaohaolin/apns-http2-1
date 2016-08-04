package br.com.zup.push.proxy;

import io.netty.handler.proxy.ProxyHandler;

public interface ProxyHandlerFactory {

    ProxyHandler createProxyHandler();
}
