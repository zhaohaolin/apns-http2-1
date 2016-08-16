package br.com.zup.push.proxy;

//import java.util.Optional;

public class ProxyConfig {
	
	public static final String SOCKS_4 = "SOCKS_4";
	public static final String SOCKS_5 = "SOCKS_5";
	public static final String HTTP = "HTTP";
	
//	final private Optional<String> username;
//	final private Optional<String> password;
	final private String address;
	final private int port;
	final private String protocol;
	

	public ProxyConfig(String username, String password, String address,
			int port, String protocol) {
		super();
//		this.username = Optional.ofNullable(username);
//		this.password = Optional.ofNullable(password);
		this.address = address;
		this.port = port;
		this.protocol = protocol;
	}
	
//	public Optional<String> getUsername() {
//		return username;
//	}
//
//	public Optional<String> getPassword() {
//		return password;
//	}

	public String getAddress() {
		return address;
	}
	public int getPort() {
		return port;
	}
	
	public String getProtocol() {
		return protocol;
	}
}