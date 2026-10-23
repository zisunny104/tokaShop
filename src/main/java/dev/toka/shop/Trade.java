package dev.toka.shop;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.item.Item;
import java.util.UUID;

/** Buying and selling for one player. Money moves first on a purchase and last on a sale, and a failure on either side puts the other back. */
final class Trade {
    private final Main plugin;

    /** Messages and money are asked from the plugin each time, so a reload reaches the next trade */
    Trade(Main plugin) { this.plugin = plugin; }

    /** The item for a product, or null when the server does not know its id */
    static Item item(Product product, int count) {
        try {
            Item item = null;
            // the game's own table of identifiers first; the old name lookup only knows the names the core was compiled with
            var entry = cn.nukkit.item.RuntimeItems.getMapping().fromIdentifier("minecraft:" + product.id());
            if (entry != null) item = Item.get(entry.getLegacyId(), product.damage() > 0 ? product.damage() : entry.getDamage());
            if (item == null || item.isNull() || item.getId() == 0) {
                item = Item.fromString(product.id());
                if (item != null && product.damage() > 0) item.setDamage(product.damage());
            }
            if (item == null || item.isNull() || item.getId() == 0) return null;
            item.setCount(count);
            return item;
        } catch (RuntimeException | LinkageError unknown) { return null; }
    }

    private String name(Product p) { return p.name(plugin.language()); }

    void buy(Player player, Product product, int pieces, int taxPercent) {
        if (!plugin.money().present()) { say(player, plugin.messages().text("shop.no_economy")); return; }
        if (!product.canBuy()) { say(player, plugin.messages().text("shop.closed_buy", name(product))); return; }
        Item item = item(product, pieces);
        if (item == null) { say(player, plugin.messages().text("shop.unknown", product.key())); return; }
        if (!player.getInventory().canAddItem(item)) { say(player, plugin.messages().text("shop.no_space", name(product), pieces)); return; }
        long total = Pricing.buyTotal(product.buy(), product.pack(), pieces);
        long tax = Pricing.tax(total, taxPercent);
        UUID id = player.getUniqueId();
        String operation = "shop-buy:" + UUID.randomUUID();
        plugin.money().withdraw(id, total + tax, "buy:" + product.key(), operation).thenAccept(paid -> run(() -> {
            if (!paid.ok()) {
                say(player, paid.status().equals("INSUFFICIENT_FUNDS") ? plugin.messages().text("shop.broke", money(total + tax), money(plugin.balanceOf(id))) : plugin.messages().text("shop.failed", paid.status()));
                return;
            }
            Item[] left = player.getInventory().addItem(item);
            for (Item rest : left) player.getLevel().dropItem(player, rest);
            if (tax > 0) plugin.payTax(tax, operation);
            String taxPart = tax > 0 ? plugin.messages().text("shop.bought_tax", money(tax)) : "";
            say(player, plugin.messages().text("shop.bought", name(product), pieces, money(total + tax), taxPart, money(paid.balance())));
        }));
    }

    void sell(Player player, Product product, int pieces) {
        if (!plugin.money().present()) { say(player, plugin.messages().text("shop.no_economy")); return; }
        if (!product.canSell()) { say(player, plugin.messages().text("shop.closed_sell", name(product))); return; }
        Item wanted = item(product, 1);
        if (wanted == null) { say(player, plugin.messages().text("shop.unknown", product.key())); return; }
        int have = 0;
        for (Item held : player.getInventory().getContents().values()) if (held.equals(wanted, true, true)) have += held.getCount();
        if (have < pieces) { say(player, plugin.messages().text("shop.too_few", name(product), have)); return; }
        Item take = item(product, pieces);
        Item[] failed = player.getInventory().removeItem(take);
        if (failed.length > 0) {
            // a stack changed between the count and the removal: put back what was taken and give up
            int missing = 0;
            for (Item f : failed) missing += f.getCount();
            if (pieces - missing > 0) player.getInventory().addItem(item(product, pieces - missing));
            say(player, plugin.messages().text("shop.too_few", name(product), have - missing));
            return;
        }
        long total = Pricing.sellTotal(product.sell(), product.pack(), pieces);
        plugin.money().deposit(player.getUniqueId(), total, "sell:" + product.key(), "shop-sell:" + UUID.randomUUID()).thenAccept(paid -> run(() -> {
            if (!paid.ok()) {
                for (Item rest : player.getInventory().addItem(item(product, pieces))) player.getLevel().dropItem(player, rest);
                say(player, plugin.messages().text("shop.failed", paid.status()));
                return;
            }
            say(player, plugin.messages().text("shop.sold", name(product), pieces, money(total), money(paid.balance())));
        }));
    }

    /** The result goes to the chat as the record, and the same line, without the shop's tag, to the top of the screen */
    private void say(Player player, String line) {
        player.sendMessage(line);
        if (!plugin.topNotice()) return;
        String top = line.replaceFirst("^§.\\[[^\\]]*\\]\\s*", "");
        try {
            var flash = Class.forName("dev.toka.display.Main").getMethod("flash", Player.class, String.class, int.class, float.class, String.class);
            flash.invoke(null, player, top, 0, -1f, null);
        } catch (ReflectiveOperationException | LinkageError noDisplay) { player.sendActionBar(top); }
    }

    private String money(long value) { return String.format("%,d", value) + " " + plugin.messages().text("shop.unit"); }

    private void run(Runnable task) {
        Server.getInstance().getScheduler().scheduleTask(plugin, task);
    }
}
