package dev.jadxmcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.jadxmcp.fixture.FixtureApk;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.core.xmlgen.entry.ProtoValue;
import jadx.core.xmlgen.entry.ResourceEntry;

class ResourceTableIndexTest {

	private JadxDecompiler jadx;

	@BeforeEach
	void load() throws Exception {
		JadxArgs args = new JadxArgs();
		args.setInputFile(FixtureApk.apk().toFile());
		jadx = new JadxDecompiler(args);
		jadx.load();
	}

	@AfterEach
	void unload() {
		if (jadx != null) {
			jadx.close();
		}
	}

	@Test
	void findByIdHexAndDecimal() {
		ResourceEntry appName = new ResourceEntry(0x7f0e0001, "dev.jadxmcp.fixture", "string", "app_name", "");
		appName.setProtoValue(new ProtoValue("Fixture App"));
		ResourceEntry appNameLand = new ResourceEntry(0x7f0e0001, "dev.jadxmcp.fixture", "string", "app_name", "land");
		ResourceEntry layout = new ResourceEntry(0x7f030000, "dev.jadxmcp.fixture", "layout", "activity_main", "");
		ResourceTableIndex idx = ResourceTableIndex.of(List.of(appName, appNameLand, layout));

		assertNotNull(idx.findById("0x7f0e0001"));
		assertNotNull(idx.findById("0X7F0E0001"));
		assertNotNull(idx.findById("2131623937")); // decimal of 0x7f0e0001
		assertEquals("Fixture App", ResourceTableIndex.valueOf(idx.findById("0x7f0e0001")));
		assertEquals(layout, idx.findById("0x7f030000"));
		assertNull(idx.findById("0x1"));
		assertNull(idx.findById("not-an-id"));
		assertNull(idx.findById(null));
	}

	@Test
	void idOfTypeAndKey() {
		ResourceEntry e = new ResourceEntry(0x7f0e0001, "pkg", "string", "app_name", "");
		ResourceTableIndex idx = ResourceTableIndex.of(List.of(e));
		assertEquals(0x7f0e0001, idx.idOf("string", "app_name"));
		assertNull(idx.idOf("string", "other"));
		assertNull(idx.idOf(null, "app_name"));
	}

	@Test
	void inputWithoutArscYieldsEmptyIndex() {
		Path apk = FixtureApk.apk();
		ResourceTableIndex idx = ResourceTableIndex.decode(jadx, apk);
		assertNotNull(idx);
		assertTrue(idx.isEmpty());
		assertNull(idx.findById("0x7f0e0001"));
	}
}
