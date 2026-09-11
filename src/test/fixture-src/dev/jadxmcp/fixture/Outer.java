package dev.jadxmcp.fixture;

public class Outer {

	private final Codec codec = new CryptoUtil();

	public class Inner {

		public int multiply(int a, int b) {
			return a * b;
		}
	}

	public int run() {
		Inner inner = new Inner();
		return inner.multiply(2, 21) + codec.encode(new byte[] { 1 }, new byte[] { 1 }).length;
	}
}
