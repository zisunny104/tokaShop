package dev.toka.shop;

import java.util.Map;

/**
 * One thing the shop can sell. A price of -1 means that side is closed (it cannot be bought, or cannot be sold back); nothing else
 * below 1 is a price. {@code pack} is how many pieces one pack holds: 16, 32 or 64, and 1 for what cannot be stacked.
 * The prices are for one pack.
 */
public record Product(String key, String id, int damage, String category, String section, String family, String variant, int tier, int pack,
                      long buy, long sell, String icon, Map<String, String> names, Map<String, String> familyNames) {
    public Product {
        names = Map.copyOf(names);
        familyNames = familyNames == null ? Map.of() : Map.copyOf(familyNames);
        if (pack < 1) throw new IllegalArgumentException("pack");
        buy = Math.max(-1, buy);
        sell = Math.max(-1, sell);
        variant = variant == null ? "" : variant;
    }

    public boolean canBuy() { return buy > 0; }

    public boolean canSell() { return sell > 0; }

    public Product withPrices(long newBuy, long newSell) {
        return new Product(key, id, damage, category, section, family, variant, tier, pack, newBuy, newSell, icon, names, familyNames);
    }

    public Product withName(String language, String name) {
        var all = new java.util.HashMap<>(names);
        all.put(language, name);
        return new Product(key, id, damage, category, section, family, variant, tier, pack, buy, sell, icon, all, familyNames);
    }

    /** The name shown to people: the one of the language, else the English one, else the id. */
    public String name(String language) {
        return names.getOrDefault(language, names.getOrDefault("en-US", id));
    }

    /** The name of the whole family (a colour or wood is left out), or the name of the product when it has no family. */
    public String familyName(String language) {
        return familyNames.isEmpty() ? name(language) : familyNames.getOrDefault(language, familyNames.getOrDefault("en-US", name(language)));
    }
}
