package dev.jadxmcp.fixture;

public class DataStore {

	public static final String API_ENDPOINT = "https://api.fixture.example.com/v1/data";

	private final CryptoUtil crypto = new CryptoUtil();

	public String fetch(String name) {
		String cacheKey = "cache:" + name;
		byte[] data = name.getBytes();
		byte[] encoded = crypto.encode(data, new byte[] { 7 });
		return cacheKey + encoded.length;
	}

	public String describe() {
		return "DataStore holds encrypted payloads for " + API_ENDPOINT;
	}
}
