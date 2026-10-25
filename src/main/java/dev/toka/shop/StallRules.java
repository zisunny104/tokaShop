package dev.toka.shop;

/** The rules of the personal shops that need no server: names, bounds for what a form sends, and the money arithmetic. */
public final class StallRules {
    private StallRules() {}

    public static final int NAME_MAX = 24;
    /** Most pieces one product line can hold */
    public static final int STOCK_MAX = 1_000_000;

    /** A name as it is kept: colour codes, section signs and control characters removed, spaces folded, cut to {@link #NAME_MAX} */
    public static String cleanName(String raw) {
        if (raw == null) return "";
        String text = raw.replaceAll("§.?", "").replaceAll("[\\p{Cntrl}\\p{Cf}]", "").replaceAll("\\s+", " ").trim();
        if (text.codePointCount(0, text.length()) > NAME_MAX) text = text.substring(0, text.offsetByCodePoints(0, NAME_MAX)).trim();
        return text;
    }

    /** What two names are compared by */
    public static String nameKey(String name) { return cleanName(name).toLowerCase(java.util.Locale.ROOT); }

    public static boolean validName(String clean) { return clean != null && !clean.isEmpty() && clean.codePointCount(0, clean.length()) <= NAME_MAX; }

    /** A whole number from a form text within min..max, or null (empty, not a number, outside) */
    public static Long parseNumber(String text, long min, long max) {
        if (text == null) return null;
        try {
            long value = Long.parseLong(text.trim());
            return value >= min && value <= max ? value : null;
        } catch (NumberFormatException bad) { return null; }
    }

    /** Whether one more of something fits under a limit; a limit below 1 is read as 1 */
    public static boolean canAddOne(int have, int limit) { return have < Math.max(1, limit); }

    /** Price times pieces, or -1 when it does not fit a long */
    public static long total(long pricePerPiece, long pieces) {
        if (pricePerPiece < 1 || pieces < 1) return -1;
        try { return Math.multiplyExact(pricePerPiece, pieces); } catch (ArithmeticException tooBig) { return -1; }
    }

    /** Pieces from a stacks box and a pieces box, or -1 when either is bad or the sum leaves 1..most */
    public static long pieces(Long stacks, Long loose, int stackSize, long most) {
        if (stacks == null || loose == null || stacks < 0 || loose < 0) return -1;
        long sum = stacks * Math.max(1, stackSize) + loose;
        return sum >= 1 && sum <= most ? sum : -1;
    }
}
