package dev.jadxmcp.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Compact class structure without decompiled source. The preferred class
 * inspection API for agents.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClassOutline(
		String id,
		String name,
		String packageName,
		String access,
		String superclass,
		List<String> interfaces,
		List<FieldEntry> fields,
		List<MethodEntry> methods,
		List<ClassEntry> innerClasses,
		String outerClass) {
}
