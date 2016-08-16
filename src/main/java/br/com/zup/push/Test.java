/*
 * CopyRight (c) 2012-2015 Hikvision Co, Ltd. All rights reserved. Filename:
 * Test.java Creator: joe.zhao(zhaohaolin@hikvision.com.cn) Create-Date:
 * 下午6:36:41
 */
package br.com.zup.push;

import io.netty.util.concurrent.Future;

import java.io.File;
import java.util.concurrent.ExecutionException;

import javax.net.ssl.SSLException;

import br.com.zup.push.client.ApnsHttp;
import br.com.zup.push.client.CertificateNotValidException;
import br.com.zup.push.data.PushNotificationResponse;
import br.com.zup.push.data.ZupHttpPushNotification;
import br.com.zup.push.notification.Notification;

/**
 * TODO
 * 
 * @author joe.zhao(zhaohaolin@hikvision.com.cn)
 * @version $Id: Test, v 0.1 2016年8月15日 下午6:36:41 Exp $
 */
public class Test {
	
	public static void main(String[] args) throws SSLException,
			ExecutionException, CertificateNotValidException {
		String filePath = "C:\\Users\\zhaohaolin.HIK\\git\\apns-http2\\src\\test\\java\\videogo_12_distribution.p12";
		File certificateFile = new File(filePath);
		ApnsHttp client = new ApnsHttp(certificateFile, "hikvision");
		client.productionMode();
		
		Notification n = new Notification.Builder(
				"1bc49dd4bcb256fedc838810870e827dd2ae04c4a0a2a17f2d34971b18685fc7")
				.alertBody(
						"U0001f42f我是来测试超长内容的我是来测试超长内容的我是来测试超长内容的我是来测试超长内容的我是来测试超长内容的我是来测试超长内容的我是来测试超长内容的")
				.badge(22)
				.sound("default.caf")
				.customField(
						"ext",
						"1,2016-07-28 16:55:14,0,1,10000, shipin7://rtsp://183.136.184.7:8554/demo://580145213:2:1:1:0:cas.ys7.com:65000&subserial=580145213&channelno=2&squareid=881880,,,,0")
				.build();
		
		// sync
		{
			// PushNotificationResponse<ZupHttpPushNotification> resp = client
			// .pushMessageSync(n.getPayload(), n.getToken());
			// System.out.println(resp);
		}
		
		// async
		{
			for (int i = 0; i < 1; i++) {
				Future<PushNotificationResponse<ZupHttpPushNotification>> future = client
						.pushMessageAsync(n.getPayload(), n.getToken());
				try {
					PushNotificationResponse<ZupHttpPushNotification> resp = future
							.get();
					System.out.println(resp.getRejectionReason());
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
		
	}
}
