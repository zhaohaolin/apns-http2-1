/*
 * CopyRight (c) 2012-2015 Hikvision Co, Ltd. All rights reserved. Filename:
 * Test.java Creator: joe.zhao(zhaohaolin@hikvision.com.cn) Create-Date:
 * 下午6:36:41
 */
package com.test;

import java.io.File;
import java.util.concurrent.ExecutionException;

import javax.net.ssl.SSLException;

import br.com.zup.push.APNsClient;
import br.com.zup.push.client.CertificateNotValidException;
import br.com.zup.push.data.APNsCallBack;
import br.com.zup.push.data.PushResponse;
import br.com.zup.push.notification.Notification;

/**
 * TODO
 * 
 * @author joe.zhao(zhaohaolin@hikvision.com.cn)
 * @version $Id: Test, v 0.1 2016年8月15日 下午6:36:41 Exp $
 */
public class Test {
	
	public static void main(String[] args) throws SSLException,
			ExecutionException, CertificateNotValidException,
			InterruptedException {
		
		String filePath = "";
		File certificateFile = new File(filePath);
		APNsClient client = new APNsClient(certificateFile, "", true,
				10);
		
		client.start();
		
		// Thread.sleep(1000);
		
		// 94b8e0dcfc406b62f3c87b5701d0c8d1af98a70319c825e8d04bc1cc7d9053c3
		// zhaohaolin iphone
		// 1bc49dd4bcb256fedc838810870e827dd2ae04c4a0a2a17f2d34971b18685fc7
		
		// sync
		{
			// PushNotificationResponse<ZupHttpPushNotification> resp = client
			// .pushMessageSync(n.getPayload(), n.getToken());
			// System.out.println(resp);
		}
		
		// async
		{
			for (int i = 0; i < 10; i++) {
				try {
					Notification n = new Notification.Builder(
							"1bc49dd4bcb256fedc838810870e827dd2ae04c4a0a2a17f2d34971b18685fc7")
							// .alertTitle("我是测试的标题")
							// .alertBody(
							// "d83dde04 我是来测试超长内容的我是来测试超长内容的我是来测试超长内容的我是来测试超长内容的我是来测试超长内容的我是来测试超长内容的我是来测试超长内容的")
							.alert("我是测试内容 i=" + i)
							// .badge(22)
							.sound("default.caf")
							.customField(
									"ext",
									"1,2016-07-28 16:55:14,0,1,10000, shipin7://rtsp://183.136.184.7:8554/demo://580145213:2:1:1:0:cas.ys7.com:65000&subserial=580145213&channelno=2&squareid=881880,,,,0")
							.build();
					client.send(n.getToken(), n.getPayload(),
							new APNsCallBack() {
								
								@Override
								public void response(PushResponse response) {
									System.out.println(response);
								}
							});
					
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
		
	}
}
