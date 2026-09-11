package dev.jadxmcp.model;

/**
 * Pagination metadata attached to every list/search result.
 */
public record Page(int offset, int limit, int total, boolean hasMore, Integer nextOffset) {

	public static Page of(int offset, int limit, int total) {
		boolean hasMore = offset + limit < total;
		return new Page(offset, limit, total, hasMore, hasMore ? offset + limit : null);
	}
}
