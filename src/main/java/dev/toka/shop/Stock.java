package dev.toka.shop;

import java.util.ArrayList;
import java.util.List;

/**
 * What a stall holds: product lines, each an exact item (id, damage and the whole NBT, so custom name, lore and enchantments are part of
 * the identity), a count and a price per piece. Two stacks with the same identity are one line. A line that reaches 0 is removed.
 */
public final class Stock {
    /** {@code tag} is the base64 of the item NBT, empty for none; {@code label} is what people see */
    public record Line(String key, int id, int damage, String tag, String label, long price, int count) {
        public static String keyOf(int id, int damage, String tag) { return id + ":" + damage + ":" + tag; }

        Line with(long newPrice, int newCount) { return new Line(key, id, damage, tag, label, newPrice, newCount); }
    }

    public enum Result { OK, FULL, TOO_MANY, MISSING }

    private final List<Line> lines = new ArrayList<>();

    public List<Line> lines() { return List.copyOf(lines); }

    public int size() { return lines.size(); }

    public boolean empty() { return lines.isEmpty(); }

    public Line find(String key) {
        for (Line line : lines) if (line.key().equals(key)) return line;
        return null;
    }

    /** Puts pieces in; an equal line is topped up and takes the new price. {@code maxLines} limits the distinct products. */
    public Result add(Line template, int pieces, long price, int maxLines) {
        if (pieces < 1) return Result.MISSING;
        Line have = find(template.key());
        if (have == null) {
            if (lines.size() >= Math.max(1, maxLines)) return Result.FULL;
            lines.add(template.with(price, pieces));
            return Result.OK;
        }
        if ((long) have.count() + pieces > StallRules.STOCK_MAX) return Result.TOO_MANY;
        lines.set(lines.indexOf(have), have.with(price, have.count() + pieces));
        return Result.OK;
    }

    /** Takes pieces out (a sale or the owner taking them back); false and no change when there are fewer */
    public boolean take(String key, int pieces) {
        Line have = find(key);
        if (have == null || pieces < 1 || have.count() < pieces) return false;
        int at = lines.indexOf(have);
        if (have.count() == pieces) lines.remove(at); else lines.set(at, have.with(have.price(), have.count() - pieces));
        return true;
    }

    public boolean price(String key, long price) {
        Line have = find(key);
        if (have == null || price < 1) return false;
        lines.set(lines.indexOf(have), have.with(price, have.count()));
        return true;
    }

    public long pieces() {
        long sum = 0;
        for (Line line : lines) sum += line.count();
        return sum;
    }

    /** Lines whose label contains the word, any case; all lines for an empty word */
    public List<Line> search(String word) {
        String w = word == null ? "" : word.trim().toLowerCase(java.util.Locale.ROOT);
        List<Line> out = new ArrayList<>();
        for (Line line : lines) if (w.isEmpty() || line.label().toLowerCase(java.util.Locale.ROOT).contains(w)) out.add(line);
        return out;
    }
}
