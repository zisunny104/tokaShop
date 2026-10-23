package dev.toka.shop;

/** Money is whole numbers. What a buyer pays is rounded up, what a seller gets is rounded down, so a part of a pack never costs less than its share. */
public final class Pricing {
    private Pricing() {}

    /** The price of {@code pieces} pieces when a pack of {@code pack} costs {@code packPrice} */
    public static long buyTotal(long packPrice, int pack, long pieces) {
        return Math.max(1, ceilDiv(packPrice * pieces, pack));
    }

    public static long sellTotal(long packPrice, int pack, long pieces) {
        return Math.max(0, packPrice * pieces / pack);
    }

    /** The consumption tax on a purchase, whole numbers, rounded up; nothing when the rate is 0 */
    public static long tax(long total, int percent) {
        return percent <= 0 ? 0 : ceilDiv(total * percent, 100);
    }

    /** A price is -1 (that side is closed) or a whole number of 1 or more */
    public static boolean validPrice(long value) { return value == -1 || value >= 1; }

    /** The price in a text, or null when it is not a valid price */
    public static Long parsePrice(String text) {
        if (text == null) return null;
        try {
            long value = Long.parseLong(text.trim());
            return validPrice(value) ? value : null;
        } catch (NumberFormatException bad) { return null; }
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }
}
