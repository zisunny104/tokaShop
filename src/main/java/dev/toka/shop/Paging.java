package dev.toka.shop;

/** Page arithmetic shared by the chat help and the windows. */
public final class Paging {
    private Paging() {}

    public static int pages(int total, int perPage) { return Math.max(1, (total + Math.max(1, perPage) - 1) / Math.max(1, perPage)); }

    /** The page asked for, kept inside 0 .. pages-1 */
    public static int clamp(int page, int pages) { return Math.min(Math.max(page, 0), Math.max(1, pages) - 1); }

    public static int from(int page, int perPage) { return page * perPage; }

    public static int to(int page, int perPage, int total) { return Math.min(total, (page + 1) * perPage); }
}
