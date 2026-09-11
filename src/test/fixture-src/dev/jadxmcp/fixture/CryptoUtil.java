package dev.jadxmcp.fixture;

public class CryptoUtil implements Codec {

	public static final String ALGORITHM = "XOR-CIPHER-V1";

	private static final byte[] SECRET_KEY = new byte[] { 0x2A, 0x37, 0x11 };

	private int rounds = 1;

	@Override
	public byte[] encode(byte[] input, byte[] key) {
		byte[] out = new byte[input.length];
		for (int i = 0; i < input.length; i++) {
			out[i] = (byte) (input[i] ^ key[i % key.length]);
		}
		return out;
	}

	public String transform(String value) {
		String prefix = "transformed-";
		return prefix + value.trim().toUpperCase();
	}

	public byte[] secret() {
		return SECRET_KEY;
	}
}
