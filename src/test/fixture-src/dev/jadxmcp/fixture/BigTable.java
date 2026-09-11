package dev.jadxmcp.fixture;

/** Large enough to exercise source truncation in tests. */
public class BigTable {

	public static final String LABEL = "big-table";

	private final int[] slots = new int[64];

	public int slot0(int v) {
		slots[0] = v * 2 + 1;
		return slots[0] ^ 0x0F;
	}

	public int slot1(int v) {
		slots[1] = v * 3 + 2;
		return slots[1] ^ 0x1F;
	}

	public int slot2(int v) {
		slots[2] = v * 5 + 3;
		return slots[2] ^ 0x2F;
	}

	public int slot3(int v) {
		slots[3] = v * 7 + 4;
		return slots[3] ^ 0x3F;
	}

	public int slot4(int v) {
		slots[4] = v * 11 + 5;
		return slots[4] ^ 0x4F;
	}

	public int slot5(int v) {
		slots[5] = v * 13 + 6;
		return slots[5] ^ 0x5F;
	}

	public int slot6(int v) {
		slots[6] = v * 17 + 7;
		return slots[6] ^ 0x6F;
	}

	public int slot7(int v) {
		slots[7] = v * 19 + 8;
		return slots[7] ^ 0x7F;
	}

	public int slot8(int v) {
		slots[8] = v * 23 + 9;
		return slots[8] ^ 0x8F;
	}

	public int slot9(int v) {
		slots[9] = v * 29 + 10;
		return slots[9] ^ 0x9F;
	}

	public int slot10(int v) {
		slots[10] = v * 31 + 11;
		return slots[10] ^ 0xAF;
	}

	public int slot11(int v) {
		slots[11] = v * 37 + 12;
		return slots[11] ^ 0xBF;
	}

	public int slot12(int v) {
		slots[12] = v * 41 + 13;
		return slots[12] ^ 0xCF;
	}

	public int slot13(int v) {
		slots[13] = v * 43 + 14;
		return slots[13] ^ 0xDF;
	}

	public int slot14(int v) {
		slots[14] = v * 47 + 15;
		return slots[14] ^ 0xEF;
	}

	public int slot15(int v) {
		slots[15] = v * 53 + 16;
		return slots[15] ^ 0xFF;
	}

	public String label() {
		return LABEL + "-" + slots.length;
	}
}
