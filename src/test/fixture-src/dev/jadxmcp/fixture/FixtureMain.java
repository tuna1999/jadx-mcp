package dev.jadxmcp.fixture;

public class FixtureMain {

	public static void main(String[] args) {
		DataStore store = new DataStore();
		CryptoUtil util = new CryptoUtil();
		System.out.println(store.fetch("sample"));
		System.out.println(util.transform("fixture"));
	}
}
