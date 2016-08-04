package br.com.zup.push;

import java.io.InputStream;
import java.util.Random;
import java.util.concurrent.ExecutionException;

import javax.net.ssl.SSLException;

import org.junit.Test;

import br.com.zup.push.client.ApnsHttp;
import br.com.zup.push.client.CertificateNotValidException;
import br.com.zup.push.data.PushNotificationResponse;
import br.com.zup.push.data.ZupHttpPushNotification;
import br.com.zup.push.proxy.ProxyConfig;

public class ProxyTest {
	
	final static private String MESSAGE = "push message test";
	final static private String DEVICE_DOMAIN =  "br.com.zup.realwave.demo";
	
	private static final int TOKEN_LENGTH = 32;
	
	@Test
	public void sendMessageWithProxyCase() {
//		InputStream cert = getClass().getClassLoader().getResourceAsStream("certificate/br.com.zup.realwave(rw2016).p12");
//		try {
//			ProxyConfig config = new ProxyConfig("zup", "12345678",
//					"10.0.0.235", 8080, ProxyConfig.HTTP);
//			ApnsHttp apnsHttp = new ApnsHttp(cert, "rw2016").sandboxMode()
//					.withProxy(config);
//			PushNotificationResponse<ZupHttpPushNotification> response = apnsHttp
//					.pushMessageSync(message, token);
//
//			response.toString();
//		} catch (SSLException e) {
//			e.printStackTrace();
//		} catch (ExecutionException e) {
//			e.printStackTrace();
//		} catch (CertificateNotValidException e) {
//			e.printStackTrace();
//		}
	}

	  
	  private void sendByUsezupApns(String token, String message) {
			
			

		     
		}
	  
	    private static String generateRandomToken() {
	        final byte[] tokenBytes = new byte[TOKEN_LENGTH];
	        new Random().nextBytes(tokenBytes);

	        final StringBuilder builder = new StringBuilder(TOKEN_LENGTH * 2);

	        for (final byte b : tokenBytes) {
	            final String hexString = Integer.toHexString(b & 0xff);

	            if (hexString.length() == 1) {
	                builder.append('0');
	            }

	            builder.append(hexString);
	        }

	        return builder.toString();
	    }

}
