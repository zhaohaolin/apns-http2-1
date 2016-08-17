/*
 * CopyRight (c) 2012-2015 Hikvision Co, Ltd. All rights reserved.
 * Filename:    Test1.java
 * Creator:     joe.zhao(zhaohaolin@hikvision.com.cn)
 * Create-Date: 下午7:28:54
 */
package com.test;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;

/**
 * TODO
 * 
 * @author joe.zhao(zhaohaolin@hikvision.com.cn)
 * @version $Id: Test1, v 0.1 2016年8月15日 下午7:28:54 Exp $
 */
public class Test1 {
	
	public static void main(String[] args) {
		Gson gson = new Gson();
		
		Map<String, String> map = new HashMap<String, String>();
		map.put("key1", "value1");
		map.put("key2", "value2");
		map.put("key3", "value3");
		
		System.out.println(gson.toJson(map));
		
		String s = "C=US, O=\"Hangzhou Ezviz Network Co., Ltd\", OU=SRN4RNBX5L, CN=Apple Push Services: com.hikvision.videogo, UID=com.hikvision.videogo";
		
		Object obj = gson.fromJson(s, HashMap.class);
		System.out.println(obj);
	}
	
}
