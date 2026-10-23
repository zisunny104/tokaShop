package dev.toka.shop;

import cn.nukkit.Player;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shop signs, through tokaSigns by reflection: 31 buys one pack, 32 sells one pack, 33 opens the shop window. A sign keeps its product in
 * its code, which is either a product code or one of the barcodes of the old shop (barcodes.json maps those to product codes).
 */
final class SignLink {
    static final int BUY = 31, SELL = 32, MANAGE = 33;

    private final Main plugin;
    private final Map<String, String> barcodes;
    private final List<Integer> actions = new ArrayList<>(), data = new ArrayList<>();
    private Class<?> api;

    SignLink(Main plugin, Map<String, String> barcodes) {
        this.plugin = plugin;
        this.barcodes = barcodes;
    }

    boolean register() {
        if (!actions.isEmpty()) return true;
        try {
            var tokaSigns = plugin.getServer().getPluginManager().getPlugin("tokaSigns");
            if (tokaSigns == null || !tokaSigns.isEnabled()) return false;
            ClassLoader loader = tokaSigns.getClass().getClassLoader();
            api = Class.forName("dev.toka.signs.Main", true, loader);
            Class<?> action = Class.forName("dev.toka.signs.Main$Action", true, loader);
            Class<?> provider = Class.forName("dev.toka.signs.Main$DisplayDataProvider", true, loader);
            for (int type : new int[]{BUY, SELL, MANAGE}) {
                Object execute = Proxy.newProxyInstance(loader, new Class[]{action}, (p, m, a) ->
                        m.getDeclaringClass() == Object.class ? plain(p, m, a) : execute(type, (Player) a[0], a[1]));
                api.getMethod("registerAction", int.class, action).invoke(null, type, execute);
                actions.add(type);
                if (type == MANAGE) continue;
                Object resolve = Proxy.newProxyInstance(loader, new Class[]{provider}, (p, m, a) ->
                        m.getDeclaringClass() == Object.class ? plain(p, m, a) : display(type, a[1]));
                api.getMethod("registerDisplayDataProvider", int.class, provider).invoke(null, type, resolve);
                data.add(type);
            }
            return true;
        } catch (ReflectiveOperationException | LinkageError | IllegalStateException error) {
            plugin.getLogger().warning(plugin.messages().text("log.sign_not_ready", error.getClass().getSimpleName()));
            unregister();
            return false;
        }
    }

    void unregister() {
        if (api == null) return;
        try {
            for (int type : actions) api.getMethod("unregisterAction", int.class).invoke(null, type);
            for (int type : data) api.getMethod("unregisterDisplayDataProvider", int.class).invoke(null, type);
        } catch (ReflectiveOperationException | LinkageError ignored) { }
        actions.clear();
        data.clear();
    }

    private static Object plain(Object p, java.lang.reflect.Method m, Object[] a) {
        return switch (m.getName()) { case "toString" -> "tokaShop sign provider"; case "hashCode" -> System.identityHashCode(p); case "equals" -> p == a[0]; default -> null; };
    }

    private Product product(Object record) throws ReflectiveOperationException {
        Object code = record.getClass().getMethod("code").invoke(record);
        if (code == null) return null;
        String text = code.toString().trim();
        return plugin.catalogue().get(barcodes.getOrDefault(text, text));
    }

    private boolean execute(int type, Player player, Object record) {
        try {
            if (type == MANAGE) { plugin.openWindow(player); return true; }
            Product p = product(record);
            if (p == null) { player.sendMessage(plugin.messages().text("shop.unknown", "?")); return true; }
            if (!faceMatches(player, record)) return true;
            if (type == BUY) plugin.buy(player, p.key(), p.pack(), true); else plugin.sell(player, p.key(), p.pack());
            return true;
        } catch (ReflectiveOperationException error) { return false; }
    }

    /**
     * A sign trades at the price now, so what is written on it has to be that price. When the face differs (a price or a pack changed
     * since it was written, or it is an old sign), nothing is traded: the player is told and the face is rewritten.
     */
    private boolean faceMatches(Player player, Object record) throws ReflectiveOperationException {
        String[] expected;
        try {
            expected = (String[]) api.getMethod("currentText", Player.class, record.getClass()).invoke(null, player, record);
        } catch (NoSuchMethodException oldSigns) { return true; }
        if (expected == null) return true;
        var level = plugin.getServer().getLevelByName(String.valueOf(record.getClass().getMethod("world").invoke(record)));
        if (level == null) return true;
        int x = (Integer) record.getClass().getMethod("x").invoke(record), y = (Integer) record.getClass().getMethod("y").invoke(record),
                z = (Integer) record.getClass().getMethod("z").invoke(record);
        if (!(level.getBlockEntity(new cn.nukkit.math.Vector3(x, y, z)) instanceof cn.nukkit.blockentity.BlockEntitySign sign)) return true;
        if (java.util.Arrays.equals(sign.getText(), expected)) return true;
        sign.setText(expected);
        player.sendMessage(plugin.messages().text("shop.sign_changed"));
        return false;
    }

    /** What the sign shows: the name, the pack and the price of the pack. Null when the sign has nothing to show (the side is closed). */
    private String display(int type, Object record) {
        try {
            Product p = product(record);
            if (p == null) return null;
            long price = type == BUY ? p.buy() : p.sell();
            if (price < 1) return null;
            var json = new com.google.gson.JsonObject();
            json.addProperty("itemName", p.name(plugin.language()));
            json.addProperty("quantity", p.pack());
            json.addProperty("amount", price);
            return json.toString();
        } catch (ReflectiveOperationException error) { return null; }
    }
}
