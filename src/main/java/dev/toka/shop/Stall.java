package dev.toka.shop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One personal shop and everything about it, kept as one document. Changed on the main thread only. {@code owed} holds sales whose money
 * has not reached the owner yet (sale id to amount), so a failed or interrupted payout is retried with the same operation id.
 */
public final class Stall {
    public final String id;
    public final UUID owner;
    public String ownerName, name;
    /** The owner's switch, the admins' lock, and whether the claim of the sign still belongs to the owner */
    public boolean open = true, locked = false, landOk = true;
    public String world;
    public int x, y, z;
    public boolean signed = false;
    public List<String> claims = new ArrayList<>();
    public long income = 0;
    public final Map<String, Long> owed = new LinkedHashMap<>();
    public final Stock stock = new Stock();

    public Stall(String id, UUID owner, String ownerName, String name) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.name = name;
    }

    public static String newId() { return "stall-" + UUID.randomUUID(); }

    public String signKey() { return signed ? signKey(world, x, y, z) : null; }

    public static String signKey(String world, int x, int y, int z) { return world + ":" + x + ":" + y + ":" + z; }

    /** Whether buyers may buy now */
    public boolean selling(boolean landKnown) { return open && !locked && (!signed || landOk || !landKnown); }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("owner", owner.toString());
        o.addProperty("ownerName", ownerName);
        o.addProperty("name", name);
        o.addProperty("open", open);
        o.addProperty("locked", locked);
        o.addProperty("landOk", landOk);
        o.addProperty("income", income);
        if (signed) {
            o.addProperty("world", world);
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
        }
        JsonArray claimIds = new JsonArray();
        claims.forEach(claimIds::add);
        o.add("claims", claimIds);
        JsonObject owedNow = new JsonObject();
        owed.forEach(owedNow::addProperty);
        o.add("owed", owedNow);
        JsonArray rows = new JsonArray();
        for (Stock.Line line : stock.lines()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", line.id());
            row.addProperty("damage", line.damage());
            row.addProperty("tag", line.tag());
            row.addProperty("label", line.label());
            row.addProperty("price", line.price());
            row.addProperty("count", line.count());
            rows.add(row);
        }
        o.add("lines", rows);
        return o;
    }

    /** The stall of a document, or null when it cannot be read (the caller logs and skips it) */
    public static Stall fromJson(String id, JsonObject o) {
        try {
            Stall s = new Stall(id, UUID.fromString(o.get("owner").getAsString()), text(o, "ownerName"), text(o, "name"));
            s.open = flag(o, "open", true);
            s.locked = flag(o, "locked", false);
            s.landOk = flag(o, "landOk", true);
            s.income = o.has("income") ? o.get("income").getAsLong() : 0;
            if (o.has("world")) {
                s.signed = true;
                s.world = o.get("world").getAsString();
                s.x = o.get("x").getAsInt();
                s.y = o.get("y").getAsInt();
                s.z = o.get("z").getAsInt();
            }
            if (o.has("claims")) for (JsonElement e : o.getAsJsonArray("claims")) s.claims.add(e.getAsString());
            if (o.has("owed")) o.getAsJsonObject("owed").entrySet().forEach(e -> s.owed.put(e.getKey(), e.getValue().getAsLong()));
            if (o.has("lines")) for (JsonElement e : o.getAsJsonArray("lines")) {
                JsonObject row = e.getAsJsonObject();
                int itemId = row.get("id").getAsInt(), damage = row.get("damage").getAsInt();
                String tag = text(row, "tag");
                s.stock.add(new Stock.Line(Stock.Line.keyOf(itemId, damage, tag), itemId, damage, tag, text(row, "label"), 0, 0),
                        row.get("count").getAsInt(), row.get("price").getAsLong(), Integer.MAX_VALUE);
            }
            return StallRules.validName(s.name) ? s : null;
        } catch (RuntimeException unreadable) { return null; }
    }

    private static String text(JsonObject o, String key) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : ""; }

    private static boolean flag(JsonObject o, String key, boolean fallback) { return o.has(key) ? o.get(key).getAsBoolean() : fallback; }
}
