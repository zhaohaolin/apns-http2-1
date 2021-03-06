/*
 * Copyright (c) 2016, CleverTap All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * - Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 * 
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * 
 * - Neither the name of CleverTap nor the names of its contributors may be used
 * to endorse or promote products derived from this software without specific
 * prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package br.com.zup.push.notification;

import java.util.HashMap;
import java.util.Map;

/**
 * A collection of all the HTTP status codes returned by Apple.
 */
public enum NotificationRequestError {
	
	BadRequest(400),
	
	CertificateError(403),
	
	BadMethod(405),
	
	DeviceTokenInactiveForTopic(410),
	
	PayloadTooLarge(413),
	
	TooManyRequestsForToken(429),
	
	InternalServerError(500),
	
	ServerUnavailable(503);
	
	public final int	errorCode;
	
	NotificationRequestError(int errorCode) {
		this.errorCode = errorCode;
	}
	
	private static Map<Integer, NotificationRequestError>	errorMap	= new HashMap<>();
	
	static {
		for (NotificationRequestError notificationRequestError : NotificationRequestError
				.values()) {
			errorMap.put(notificationRequestError.errorCode,
					notificationRequestError);
		}
	}
	
	public static NotificationRequestError get(int errorCode) {
		return errorMap.get(errorCode);
	}
}
