package dev.toka.shop;

import cn.nukkit.Player;
import cn.nukkit.plugin.Plugin;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** What the shop asks tokaLand, by reflection so that it loads without it. Anything unclear is "unknown", never "allowed". */
final class LandLink {
    /** {@code known} is false when tokaLand could not answer; {@code claims} are the claims at the spot */
    record Standing(boolean known, boolean allowed, List<String> claims) {
        static final Standing UNKNOWN = new Standing(false, false, List.of());
    }

    private final Main plugin;

    LandLink(Main plugin) { this.plugin = plugin; }

    private Plugin land() {
        Plugin land = plugin.getServer().getPluginManager().getPlugin("tokaLand");
        return land != null && land.isEnabled() ? land : null;
    }

    boolean installed() { return land() != null; }

    private Method method(String name, Class<?>... parameters) {
        Plugin land = land();
        if (land == null) return null;
        try { return Class.forName("dev.toka.land.Main", true, land.getClass().getClassLoader()).getMethod(name, parameters); }
        catch (ReflectiveOperationException | LinkageError missing) { return null; }
    }

    /** Whether the player may manage the claims at the block (owner, renter or someone given manage rights). Main thread. */
    Standing manage(Player player, String world, int x, int y, int z) {
        Method query = method("queryProtectionJson", Player.class, String.class, int.class, int.class, int.class, String.class);
        if (query == null || !player.isOnline()) return Standing.UNKNOWN;
        try {
            JsonObject reply = JsonParser.parseString(String.valueOf(query.invoke(null, player, world, x, y, z, "MANAGE"))).getAsJsonObject();
            String status = reply.get("status").getAsString();
            if (!status.equals("ALLOW") && !status.equals("DENY")) return Standing.UNKNOWN;
            List<String> claims = new ArrayList<>();
            for (JsonElement id : reply.getAsJsonArray("claims")) claims.add(id.getAsString());
            return new Standing(true, status.equals("ALLOW") && !claims.isEmpty(), claims);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failed) { return Standing.UNKNOWN; }
    }

    /** The claims at a block, or null when unknown */
    List<String> claimsAt(String world, int x, int y, int z) {
        Method lookup = method("claimsAtJson", String.class, int.class, int.class, int.class);
        if (lookup == null) return null;
        try {
            Object json = lookup.invoke(null, world, x, y, z);
            if (json == null) return null;
            List<String> claims = new ArrayList<>();
            for (JsonElement row : JsonParser.parseString(json.toString()).getAsJsonArray()) claims.add(row.getAsJsonObject().get("id").getAsString());
            return claims;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failed) { return null; }
    }
}
