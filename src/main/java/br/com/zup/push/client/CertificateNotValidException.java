package br.com.zup.push.client;

/**
 * Created by igor on 29/06/16.
 */
public class CertificateNotValidException extends Exception {
	/**
	 * 
	 */
	private static final long	serialVersionUID	= 1L;
	private String				reason;
	
	public CertificateNotValidException(String reason) {
		this.reason = reason;
	}
	
	public String getReason() {
		return reason;
	}
}
