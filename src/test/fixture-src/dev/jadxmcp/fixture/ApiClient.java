package dev.jadxmcp.fixture;

public class ApiClient {

	private static CryptoUtil crypto = new CryptoUtil();

	public byte[] call(String payload) {
		String url = "https://api.fixture.example.com/v1/data";
		String data = url + ":" + payload;
		return crypto.encode(data.getBytes(), crypto.secret());
	}

	public String describe() {
		return "api-client-ready";
	}
}
