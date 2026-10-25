package dev.toka.shop;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every stall in memory with the three lookups the game needs: by owner, by name and by sign position. Built once at start; a click
 * on a sign or a name typed in a command is a map lookup, never a scan. Main thread only. Each change is handed to {@link Persist},
 * which writes the one document of that stall.
 */
final class StallBook {
    /** Where documents go; the plugin writes them on a background thread, a test just records them */
    interface Persist {
        void save(String key, JsonObject document);

        void delete(String key);
    }

    enum Problem { NONE, BAD_NAME, TAKEN, LIMIT }

    private final Persist persist;
    private final Map<String, Stall> byId = new LinkedHashMap<>();
    private final Map<String, Stall> byName = new HashMap<>();
    private final Map<UUID, List<Stall>> byOwner = new HashMap<>();
    private final Map<String, Stall> bySign = new HashMap<>();

    /** Called when a name appeared, changed or went (the command list offers the names) */
    Runnable namesChanged = () -> { };

    StallBook(Persist persist) { this.persist = persist; }

    /** Adds a stall read from the store; a clash of name or sign with one already known is refused and false returned */
    boolean load(Stall stall) {
        if (byId.containsKey(stall.id) || byName.containsKey(StallRules.nameKey(stall.name))) return false;
        if (stall.signed && bySign.containsKey(stall.signKey())) { stall.signed = false; stall.claims.clear(); }
        index(stall);
        return true;
    }

    private void index(Stall stall) {
        byId.put(stall.id, stall);
        byName.put(StallRules.nameKey(stall.name), stall);
        byOwner.computeIfAbsent(stall.owner, k -> new ArrayList<>()).add(stall);
        if (stall.signed) bySign.put(stall.signKey(), stall);
    }

    int size() { return byId.size(); }

    Collection<Stall> all() { return List.copyOf(byId.values()); }

    Stall byName(String name) { return byName.get(StallRules.nameKey(name)); }

    Stall bySign(String world, int x, int y, int z) { return bySign.get(Stall.signKey(world, x, y, z)); }

    Stall byId(String id) { return byId.get(id); }

    List<Stall> of(UUID owner) { return List.copyOf(byOwner.getOrDefault(owner, List.of())); }

    List<String> names() { return byId.values().stream().map(s -> s.name).toList(); }

    /** Whether a name could be used: valid, not used by any stall (the stall {@code except} may keep its own), and the owner is under the limit */
    Problem check(UUID owner, String raw, int limit, Stall except) {
        String clean = StallRules.cleanName(raw);
        if (!StallRules.validName(clean)) return Problem.BAD_NAME;
        Stall same = byName.get(StallRules.nameKey(clean));
        if (same != null && same != except) return Problem.TAKEN;
        if (except == null && !StallRules.canAddOne(of(owner).size(), limit)) return Problem.LIMIT;
        return Problem.NONE;
    }

    /** The new stall, or null (ask {@link #check} why) */
    Stall create(UUID owner, String ownerName, String raw, int limit) {
        if (check(owner, raw, limit, null) != Problem.NONE) return null;
        Stall stall = new Stall(Stall.newId(), owner, ownerName, StallRules.cleanName(raw));
        index(stall);
        save(stall);
        namesChanged.run();
        return stall;
    }

    boolean rename(Stall stall, String raw) {
        if (check(stall.owner, raw, Integer.MAX_VALUE, stall) != Problem.NONE) return false;
        byName.remove(StallRules.nameKey(stall.name));
        stall.name = StallRules.cleanName(raw);
        byName.put(StallRules.nameKey(stall.name), stall);
        save(stall);
        namesChanged.run();
        return true;
    }

    /** Ties the stall to a sign position; a stall has one sign, and a position one stall (the old stall there loses it) */
    void link(Stall stall, String world, int x, int y, int z, List<String> claims) {
        unlink(stall);
        Stall there = bySign.get(Stall.signKey(world, x, y, z));
        if (there != null) unlink(there);
        stall.signed = true;
        stall.world = world;
        stall.x = x;
        stall.y = y;
        stall.z = z;
        stall.claims = new ArrayList<>(claims);
        stall.landOk = true;
        bySign.put(stall.signKey(), stall);
        save(stall);
    }

    void unlink(Stall stall) {
        if (!stall.signed) return;
        bySign.remove(stall.signKey(), stall);
        stall.signed = false;
        stall.claims = new ArrayList<>();
        save(stall);
    }

    void remove(Stall stall) {
        byId.remove(stall.id);
        byName.remove(StallRules.nameKey(stall.name), stall);
        var list = byOwner.get(stall.owner);
        if (list != null) { list.remove(stall); if (list.isEmpty()) byOwner.remove(stall.owner); }
        if (stall.signed) bySign.remove(stall.signKey(), stall);
        persist.delete(stall.id);
        namesChanged.run();
    }

    /** Writes the stall; call after every change to it */
    void save(Stall stall) { persist.save(stall.id, stall.toJson()); }
}
