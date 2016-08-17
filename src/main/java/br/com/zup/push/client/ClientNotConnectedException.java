package br.com.zup.push.client;

public class ClientNotConnectedException extends IllegalStateException {
	private static final long	serialVersionUID	= 1L;
	
	public ClientNotConnectedException() {
		super();
	}
	
	public ClientNotConnectedException(final String message) {
		super(message);
	}
}
