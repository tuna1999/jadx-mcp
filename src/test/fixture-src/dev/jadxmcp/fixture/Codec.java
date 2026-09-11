package dev.jadxmcp.fixture;

public interface Codec {

	byte[] encode(byte[] input, byte[] key);
}
