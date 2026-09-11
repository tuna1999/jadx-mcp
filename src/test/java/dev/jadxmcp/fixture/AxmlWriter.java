package dev.jadxmcp.fixture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal Android binary XML (AXML) writer used only to produce the test
 * manifest fixture. Emits a UTF-16 string pool plus start/end namespace and
 * element chunks in the format jadx and aapt understand.
 */
public final class AxmlWriter {

	private static final int RES_STRING_POOL_TYPE = 0x0001;
	private static final int RES_XML_TYPE = 0x0003;
	private static final int RES_XML_START_NAMESPACE_TYPE = 0x0100;
	private static final int RES_XML_END_NAMESPACE_TYPE = 0x0101;
	private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;
	private static final int RES_XML_END_ELEMENT_TYPE = 0x0103;

	private static final int TYPE_STRING = 0x03;
	private static final int TYPE_INT_DEC = 0x10;
	private static final int TYPE_INT_BOOLEAN = 0x12;

	private static final int NO_INDEX = 0xFFFFFFFF;

	/** Little-endian byte sink. */
	private static final class Out {
		private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

		Out u16(int v) {
			buf.write(v & 0xFF);
			buf.write((v >>> 8) & 0xFF);
			return this;
		}

		Out u32(long v) {
			buf.write((int) (v & 0xFF));
			buf.write((int) ((v >>> 8) & 0xFF));
			buf.write((int) ((v >>> 16) & 0xFF));
			buf.write((int) ((v >>> 24) & 0xFF));
			return this;
		}

		Out i32(int v) {
			return u32(v & 0xFFFFFFFFL);
		}

		Out bytes(byte[] b) {
			buf.write(b, 0, b.length);
			return this;
		}

		byte[] bytes() {
			return buf.toByteArray();
		}

		int size() {
			return buf.size();
		}
	}

	public static final class Element {
		private final String name;
		private final List<Element> children = new ArrayList<>();
		private final List<Attr> attrs = new ArrayList<>();
		private Element parent;

		private Element(String name) {
			this.name = name;
		}

		public Element child(String name) {
			Element e = new Element(name);
			e.parent = this;
			children.add(e);
			return e;
		}

		public Element attr(String name, String value) {
			attrs.add(new Attr(name, TYPE_STRING, value));
			return this;
		}

		public Element attr(String name, int value) {
			attrs.add(new Attr(name, TYPE_INT_DEC, value));
			return this;
		}

		public Element attr(String name, boolean value) {
			attrs.add(new Attr(name, TYPE_INT_BOOLEAN, value));
			return this;
		}

		public Element end() {
			return parent;
		}
	}

	private record Attr(String name, int type, Object value) {
	}

	private final Map<String, Integer> strings = new LinkedHashMap<>();
	private final String nsPrefix;
	private final String nsUri;
	private final Element root;

	private AxmlWriter(String rootName, String nsPrefix, String nsUri) {
		this.nsPrefix = nsPrefix;
		this.nsUri = nsUri;
		this.root = new Element(rootName);
		intern("");
	}

	public static AxmlWriter document(String rootName, String nsPrefix, String nsUri) {
		return new AxmlWriter(rootName, nsPrefix, nsUri);
	}

	public Element root() {
		return root;
	}

	private int intern(String s) {
		return strings.computeIfAbsent(s, k -> strings.size());
	}

	public byte[] toBytes() throws IOException {
		// Pass 1: intern every string so indices are stable
		intern(nsPrefix);
		intern(nsUri);
		internAll(root);
		List<byte[]> chunks = new ArrayList<>();
		chunks.add(stringPoolChunk());
		chunks.add(namespaceChunk(RES_XML_START_NAMESPACE_TYPE));
		chunks.add(elementChunks(root));
		chunks.add(namespaceChunk(RES_XML_END_NAMESPACE_TYPE));

		int total = 8;
		for (byte[] c : chunks) {
			total += c.length;
		}
		Out head = new Out().u16(RES_XML_TYPE).u16(8).u32(total);
		ByteArrayOutputStream out = new ByteArrayOutputStream(total);
		out.write(head.bytes());
		for (byte[] c : chunks) {
			out.write(c, 0, c.length);
		}
		return out.toByteArray();
	}

	private void internAll(Element e) {
		intern(e.name);
		for (Attr a : e.attrs) {
			intern(localName(a.name));
			if (a.type == TYPE_STRING) {
				intern((String) a.value());
			}
		}
		for (Element c : e.children) {
			internAll(c);
		}
	}

	private String localName(String attrName) {
		int i = attrName.indexOf(':');
		return i < 0 ? attrName : attrName.substring(i + 1);
	}

	private byte[] stringPoolChunk() {
		int count = strings.size();
		List<byte[]> encoded = new ArrayList<>(count);
		int stringsBytes = 0;
		for (String s : strings.keySet()) {
			byte[] b = utf16z(s);
			encoded.add(b);
			stringsBytes += b.length;
		}
		int headerSize = 28;
		int offsetsBytes = count * 4;
		int stringsStart = headerSize + offsetsBytes;
		int size = stringsStart + stringsBytes;
		int pad = (4 - (size % 4)) % 4;
		size += pad;

		Out d = new Out()
				.u16(RES_STRING_POOL_TYPE)
				.u16(headerSize)
				.u32(size)
				.u32(count)
				.u32(0) // styleCount
				.u32(0) // flags: UTF-16
				.u32(stringsStart)
				.u32(0); // stylesStart
		int off = 0;
		for (byte[] b : encoded) {
			d.u32(off);
			off += b.length;
		}
		for (byte[] b : encoded) {
			d.bytes(b);
		}
		for (int i = 0; i < pad; i++) {
			d.buf.write(0);
		}
		return d.bytes();
	}

	private byte[] namespaceChunk(int type) {
		return new Out()
				.u16(type)
				.u16(16) // headerSize
				.u32(24) // chunk size
				.u32(1) // lineNumber
				.u32(NO_INDEX) // comment
				.u32(strings.get(nsPrefix))
				.u32(strings.get(nsUri))
				.bytes();
	}

	/** START element chunk, then its subtree recursively, then its END chunk. */
	private byte[] elementChunks(Element e) {
		Out d = new Out();
		int attrCount = e.attrs.size();
		int size = 36 + attrCount * 20;
		d.u16(RES_XML_START_ELEMENT_TYPE)
				.u16(16)
				.u32(size)
				.u32(1) // lineNumber
				.u32(NO_INDEX) // comment
				.u32(NO_INDEX) // element ns
				.u32(strings.get(e.name))
				.u16(0x14) // attributeStart
				.u16(0x14) // attributeSize
				.u16(attrCount)
				.u16(0) // idIndex
				.u16(0) // classIndex
				.u16(0); // styleIndex
		for (Attr a : e.attrs) {
			boolean androidNs = a.name.startsWith(nsPrefix + ":");
			d.u32(androidNs ? strings.get(nsUri) : NO_INDEX); // attr ns
			d.u32(strings.get(localName(a.name))); // attr name
			if (a.type == TYPE_STRING) {
				d.u32(strings.get((String) a.value())); // rawValue
			} else {
				d.u32(NO_INDEX);
			}
			d.u16(8); // value size
			d.buf.write(0); // res0
			d.buf.write(a.type); // dataType
			switch (a.type) {
				case TYPE_STRING -> d.i32(strings.get((String) a.value()));
				case TYPE_INT_DEC -> d.i32((Integer) a.value());
				case TYPE_INT_BOOLEAN -> d.i32(Boolean.TRUE.equals(a.value()) ? -1 : 0);
				default -> throw new IllegalStateException("unsupported attr type " + a.type);
			}
		}
		Out full = d;
		for (Element c : e.children) {
			full.bytes(elementChunks(c));
		}
		full.u16(RES_XML_END_ELEMENT_TYPE)
				.u16(16)
				.u32(24)
				.u32(1) // lineNumber
				.u32(NO_INDEX) // comment
				.u32(NO_INDEX) // ns
				.u32(strings.get(e.name));
		return full.bytes();
	}


	/** [u16le charCount][utf-16le chars][00 00] as written by aapt. */
	private static byte[] utf16z(String s) {
		byte[] body = s.getBytes(StandardCharsets.UTF_16LE);
		byte[] out = new byte[body.length + 4];
		out[0] = (byte) (s.length() & 0xFF);
		out[1] = (byte) ((s.length() >>> 8) & 0xFF);
		System.arraycopy(body, 0, out, 2, body.length);
		return out;
	}
}
