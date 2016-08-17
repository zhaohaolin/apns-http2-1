package br.com.zup.push.util;

public class TokenUtil {
	
	private TokenUtil() {
	}
	
	public static String sanitizeTokenString(final String tokenString) {
		return tokenString.replaceAll("[^a-fA-F0-9]", "");
	}
}
