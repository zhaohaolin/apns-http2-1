/*
 * CopyRight (c) 2012-2015 Hikvision Co, Ltd. All rights reserved. Filename:
 * SslUtils.java Creator: joe.zhao(zhaohaolin@hikvision.com.cn) Create-Date:
 * 下午4:42:59
 */
package br.com.zup.push.client;

import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLException;

import br.com.zup.push.util.P12Util;

/**
 * TODO
 * 
 * @author joe.zhao(zhaohaolin@hikvision.com.cn)
 * @version $Id: SslUtils, v 0.1 2016年9月13日 下午4:42:59 Exp $
 */
public class SslUtils {
	
	final static SslContext getSslContextWithP12File(final File p12File,
			final String password) throws IOException, KeyStoreException {
		try (final InputStream p12InputStream = new FileInputStream(p12File)) {
			return getSslContextWithP12InputStream(
					loadKeyStore(p12InputStream, password), password);
		}
	}
	
	final static SslContext getSslContextWithP12InputStream(
			final KeyStore keyStore, final String password) throws SSLException {
		final X509Certificate x509Certificate;
		final PrivateKey privateKey;
		
		try {
			final PrivateKeyEntry privateKeyEntry = P12Util
					.getFirstPrivateKeyEntryFromP12InputStream(keyStore,
							password);
			
			final Certificate certificate = privateKeyEntry.getCertificate();
			
			if (!(certificate instanceof X509Certificate)) {
				throw new KeyStoreException(
						"Found a certificate in the provided PKCS#12 file, but it was not an X.509 certificate.");
			}
			
			x509Certificate = (X509Certificate) certificate;
			privateKey = privateKeyEntry.getPrivateKey();
		} catch (final KeyStoreException e) {
			throw new SSLException(e);
		} catch (final IOException e) {
			throw new SSLException(e);
		}
		
		return getSslContextWithCertificateAndPrivateKey(x509Certificate,
				privateKey, password);
	}
	
	final static SslContext getSslContextWithCertificateAndPrivateKey(
			final X509Certificate certificate, final PrivateKey privateKey,
			final String privateKeyPassword) throws SSLException {
		return getBaseSslContextBuilder().keyManager(privateKey,
				privateKeyPassword, certificate).build();
	}
	
	final static SslContextBuilder getBaseSslContextBuilder() {
		final SslProvider sslProvider;
		
		if (OpenSsl.isAvailable()) {
			if (OpenSsl.isAlpnSupported()) {
				sslProvider = SslProvider.OPENSSL;
			} else {
				sslProvider = SslProvider.JDK;
			}
		} else {
			sslProvider = SslProvider.JDK;
		}
		
		final ApplicationProtocolConfig config = new ApplicationProtocolConfig(
				Protocol.ALPN, SelectorFailureBehavior.NO_ADVERTISE,
				SelectedListenerFailureBehavior.ACCEPT,
				ApplicationProtocolNames.HTTP_2);
		
		SslContextBuilder builder = SslContextBuilder
				.forClient()
				.sslProvider(sslProvider)
				.ciphers(Http2SecurityUtil.CIPHERS,
						SupportedCipherSuiteFilter.INSTANCE)
				.applicationProtocolConfig(config);
		return builder;
	}
	
	final static KeyStore loadKeyStore(final InputStream p12InputStream,
			final String password) throws KeyStoreException, IOException {
		try {
			return P12Util.loadPCKS12KeyStore(p12InputStream, password);
		} catch (KeyStoreException e) {
			throw e;
		} catch (IOException e) {
			throw e;
		}
	}
	
}
