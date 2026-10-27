package dev.toka.shop;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything the shop knows how to sell, as shipped in catalogue.json, with the server's own changes laid over it. */
public final class Catalogue {
    /** A change by the server: any field may be left out. {@code disabled} closes both sides. */
    public record Override(Long buy, Long sell, String name, boolean disabled) {}

    /** The colours, woods or weathering of one thing, shown as one entry */
    public record Family(String key, String section, List<Product> members) {
        public Product first() { return members.get(0); }
    }

    /** Top categories and their sections, in the order the window shows them */
    public static final Map<String, List<String>> LAYOUT = new LinkedHashMap<>();
    static {
        LAYOUT.put("建築", List.of("方塊", "色彩", "構件"));
        LAYOUT.put("自然", List.of("土石", "植物"));
        LAYOUT.put("裝飾", List.of("家具", "燈與功能", "告示牌旗幟頭顱"));
        LAYOUT.put("材料", List.of("礦物", "紅石", "染料與其他"));
        LAYOUT.put("裝備", List.of("工具與武器", "盔甲"));
        LAYOUT.put("食物", List.of("食物"));
    }

    private final Map<String, Product> products = new LinkedHashMap<>();
    private final Map<String, List<Family>> bySection = new LinkedHashMap<>();

    public Catalogue(List<Product> list) {
        for (Product p : list) products.put(p.key(), p);
        Map<String, Map<String, List<Product>>> grouped = new LinkedHashMap<>();
        for (Product p : list) grouped.computeIfAbsent(p.section(), s -> new LinkedHashMap<>()).computeIfAbsent(p.family(), f -> new ArrayList<>()).add(p);
        grouped.forEach((section, families) -> {
            List<Family> out = new ArrayList<>();
            families.forEach((key, members) -> out.add(new Family(key, section, members)));
            bySection.put(section, out);
        });
    }

    public static List<Product> read(InputStream in) throws IOException {
        try (var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject root = new Gson().fromJson(reader, JsonObject.class);
            JsonArray items = root.getAsJsonArray("items");
            List<Product> list = new ArrayList<>();
            for (JsonElement e : items) {
                JsonObject o = e.getAsJsonObject();
                list.add(new Product(o.get("key").getAsString(), o.get("id").getAsString(), o.get("damage").getAsInt(), o.get("category").getAsString(),
                        o.get("section").getAsString(), o.get("family").getAsString(), text(o, "variant"), o.get("tier").getAsInt(), o.get("pack").getAsInt(),
                        o.get("buy").getAsLong(), o.get("sell").getAsLong(), text(o, "icon"), strings(o.getAsJsonObject("names")),
                        o.has("familyNames") ? strings(o.getAsJsonObject("familyNames")) : Map.of()));
            }
            return list;
        }
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static Map<String, String> strings(JsonObject o) {
        Map<String, String> out = new LinkedHashMap<>();
        o.entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
        return out;
    }

    /** The catalogue with the server's changes applied. A price that is not -1 or at least 1 is ignored. */
    public static Catalogue with(List<Product> shipped, Map<String, Override> changes, String language) {
        List<Product> list = new ArrayList<>();
        for (Product p : shipped) {
            list.add(apply(p, changes.get(p.key()), language, false));
        }
        return new Catalogue(list);
    }

    /** One product with a change laid over it; {@code ignoreDisabled} gives the prices it would have when enabled */
    public static Product apply(Product p, Override o, String language, boolean ignoreDisabled) {
        if (o == null) return p;
        if (o.disabled() && !ignoreDisabled) p = p.withPrices(-1, -1);
        else p = p.withPrices(valid(o.buy(), p.buy()), valid(o.sell(), p.sell()));
        if (o.name() != null && !o.name().isBlank()) p = p.withName(language, o.name());
        return p;
    }

    /** Hand-picked button pictures of the top categories, block textures because the client showed the item textures as missing; config window.icons.<category> replaces one, an empty text removes it */
    public static final Map<String, String> ICONS = Map.of(
            "建築", "textures/blocks/planks_oak", "自然", "textures/blocks/sapling_oak", "裝飾", "textures/blocks/bookshelf",
            "材料", "textures/blocks/iron_block", "裝備", "textures/blocks/anvil_base", "食物", "textures/blocks/cake_side");

    private static long valid(Long wanted, long kept) {
        return wanted != null && (wanted == -1 || wanted >= 1) ? wanted : kept;
    }

    public Product get(String key) { return products.get(key); }

    public java.util.Collection<Product> all() { return products.values(); }

    public List<Family> families(String section) { return bySection.getOrDefault(section, List.of()); }

    /** What matches a word in a name (any language), a code or an id, at most {@code limit} products */
    public List<Product> search(String word, int limit) {
        String w = word.toLowerCase(java.util.Locale.ROOT);
        List<Product> out = new ArrayList<>();
        for (Product p : products.values()) {
            if (p.key().toLowerCase(java.util.Locale.ROOT).contains(w) || p.id().contains(w) || p.names().values().stream().anyMatch(n -> n.toLowerCase(java.util.Locale.ROOT).contains(w))) {
                out.add(p);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }
}
