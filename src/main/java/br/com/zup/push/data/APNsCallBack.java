/*
 * CopyRight (c) 2012-2015 Hikvision Co, Ltd. All rights reserved. Filename:
 * APNsCallBack.java Creator: joe.zhao(zhaohaolin@hikvision.com.cn) Create-Date:
 * 下午3:06:08
 */
package br.com.zup.push.data;

/**
 * TODO
 * 
 * @author joe.zhao(zhaohaolin@hikvision.com.cn)
 * @version $Id: APNsCallBack, v 0.1 2016年9月13日 下午3:06:08 Exp $
 */
public interface APNsCallBack {
	
	void response(final PushResponse response);
	
}
