package dev.jadxmcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jadxmcp.core.IndexStore.EdgeRow;
import dev.jadxmcp.core.IndexStore.StringRow;

class IndexStoreTest {

	@TempDir
	Path tmp;

	@Test
	void buildPersistAndReopen() throws Exception {
		IndexStore s = IndexStore.open(tmp, "cafebabe", "1.5.6");
		assertNotNull(s);
		assertTrue(s.needsBuild());
		s.insertStrings(List.of(new StringRow("LA;", "A", "LA;->m()V", "m", "https://api.example.com")));
		s.insertEdges("LA;->m()V", List.of(new EdgeRow("LB;->n()V", "method")));
		s.putSource("LA;", "class A {}");
		s.markReady();
		assertEquals(1, s.stringCount());
		assertEquals(1, s.edgeCount());
		s.close();

		IndexStore reopened = IndexStore.open(tmp, "cafebabe", "1.5.6");
		assertNotNull(reopened);
		assertFalse(reopened.needsBuild());
		assertTrue(reopened.awaitReady(0));
		assertEquals(1, reopened.stringCount());
		assertEquals(1, reopened.edgeCount());
		assertEquals(1, reopened.searchStrings("api.example", false, 10).size());
		assertEquals(1, reopened.searchStrings("API.EXAMPLE", false, 10).size());
		assertEquals(0, reopened.searchStrings("API.EXAMPLE", true, 10).size());
		assertEquals(1, reopened.searchStrings("api.example", true, 10).size());
		assertEquals("class A {}", reopened.cachedSource("LA;"));
		assertEquals(1, reopened.outgoingOfMethod("LA;->m()V", 10).size());
		assertEquals(0, reopened.outgoingOfMethod("LB;->n()V", 10).size());
		assertEquals(1, reopened.outgoingOfClass("LA;", 10).size());
		reopened.close();
	}

	@Test
	void outgoingQueriesReturnLimitPlusOneForTruncation() throws Exception {
		IndexStore s = IndexStore.open(tmp, "trunc", "1.5.6");
		s.insertEdges("LS;->a()V", List.of(
				new EdgeRow("LD1;->x()V", "method"),
				new EdgeRow("LD2;->x()V", "method"),
				new EdgeRow("LD3;->x()V", "method")));
		s.markReady();
		// max+1 rows signal "more available"; callers slice to max
		assertEquals(3, s.outgoingOfMethod("LS;->a()V", 2).size());
		assertEquals(3, s.outgoingOfClass("LS;", 2).size());
		assertEquals(3, s.outgoingOfMethod("LS;->a()V", 10).size());
		s.close();
	}

	@Test
	void distinctDestinationsAcrossDuplicateEdges() throws Exception {
		IndexStore s = IndexStore.open(tmp, "dedup", "1.5.6");
		s.insertEdges("LS;->a()V", List.of(new EdgeRow("LD1;->x()V", "method")));
		s.insertEdges("LS;->a()V", List.of(new EdgeRow("LD1;->x()V", "method")));
		s.markReady();
		assertEquals(1, s.outgoingOfMethod("LS;->a()V", 10).size());
		s.close();
	}

	@Test
	void versionMismatchForcesRebuild() throws Exception {
		IndexStore s = IndexStore.open(tmp, "cafebabe2", "1.5.6");
		s.markReady();
		s.close();
		IndexStore other = IndexStore.open(tmp, "cafebabe2", "1.6.0");
		assertNotNull(other);
		assertTrue(other.needsBuild());
		other.close();
	}

	@Test
	void unwritableDirReturnsNull() throws Exception {
		Path notADir = tmp.resolve("not-a-dir");
		Files.writeString(notADir, "x");
		assertNull(IndexStore.open(notADir, "x", "1.5.6"));
	}
}
