package dev.toka.shop;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.item.Item;
import dev.toka.i18n.Messages;
import java.util.UUID;

/** Buying and selling for one player. Money moves first on a purchase and last on a sale, and a failure on either side puts the other back. */
final class Trade {
    private final Main plugin;
    private final Money money;
    private final Messages messages;

    Trade(Main plugin, Money money, Messages messages) {
        this.plugin = plugin;
        this.money = money;
        this.messages = messages;
    }

    /** The item for a product, or null when the server does not know its id */
    static Item item(Product product, int count) {
        try {
            Item item = Item.fromString(product.id());
            if (item == null || item.isNull() || item.getId() == 0) return null;
            if (product.damage() > 0) item.setDamage(product.damage());
            item.setCount(count);
            return item;
        } catch (RuntimeException | LinkageError unknown) { return null; }
    }

    private String name(Product p) { return p.name(plugin.language()); }

    void buy(Player player, Product product, int pieces, int taxPercent) {
        if (!money.present()) { say(player, messages.text("shop.no_economy")); return; }
        if (!product.canBuy()) { say(player, messages.text("shop.closed_buy", name(product))); return; }
        Item item = item(product, pieces);
        if (item == null) { say(player, messages.text("shop.unknown", product.key())); return; }
        if (!player.getInventory().canAddItem(item)) { say(player, messages.text("shop.no_space", name(product), pieces)); return; }
        long total = Pricing.buyTotal(product.buy(), product.pack(), pieces);
        long tax = Pricing.tax(total, taxPercent);
        UUID id = player.getUniqueId();
        String operation = "shop-buy:" + UUID.randomUUID();
        money.withdraw(id, total + tax, "buy:" + product.key(), operation).thenAccept(paid -> run(() -> {
            if (!paid.ok()) {
                say(player, paid.status().equals("INSUFFICIENT_FUNDS") ? messages.text("shop.broke", money(total + tax), money(plugin.balanceOf(id))) : messages.text("shop.failed", paid.status()));
                return;
            }
            Item[] left = player.getInventory().addItem(item);
            for (Item rest : left) player.getLevel().dropItem(player, rest);
            if (tax > 0) plugin.payTax(tax, operation);
            String taxPart = tax > 0 ? messages.text("shop.bought_tax", money(tax)) : "";
            say(player, messages.text("shop.bought", name(product), pieces, money(total + tax), taxPart, money(paid.balance())),
                    messages.text("shop.slip_buy", name(product), pieces, money(total + tax), taxPart, money(paid.balance())));
        }));
    }

    void sell(Player player, Product product, int pieces) {
        if (!money.present()) { say(player, messages.text("shop.no_economy")); return; }
        if (!product.canSell()) { say(player, messages.text("shop.closed_sell", name(product))); return; }
        Item wanted = item(product, 1);
        if (wanted == null) { say(player, messages.text("shop.unknown", product.key())); return; }
        int have = 0;
        for (Item held : player.getInventory().getContents().values()) if (held.equals(wanted, true, true)) have += held.getCount();
        if (have < pieces) { say(player, messages.text("shop.too_few", name(product), have)); return; }
        Item take = item(product, pieces);
        Item[] failed = player.getInventory().removeItem(take);
        if (failed.length > 0) {
            // a stack changed between the count and the removal: put back what was taken and give up
            int missing = 0;
            for (Item f : failed) missing += f.getCount();
            if (pieces - missing > 0) player.getInventory().addItem(item(product, pieces - missing));
            say(player, messages.text("shop.too_few", name(product), have - missing));
            return;
        }
        long total = Pricing.sellTotal(product.sell(), product.pack(), pieces);
        money.deposit(player.getUniqueId(), total, "sell:" + product.key(), "shop-sell:" + UUID.randomUUID()).thenAccept(paid -> run(() -> {
            if (!paid.ok()) {
                for (Item rest : player.getInventory().addItem(item(product, pieces))) player.getLevel().dropItem(player, rest);
                say(player, messages.text("shop.failed", paid.status()));
                return;
            }
            say(player, messages.text("shop.sold", name(product), pieces, money(total), money(paid.balance())),
                    messages.text("shop.slip_sell", name(product), pieces, money(total), money(paid.balance())));
        }));
    }

    /** The result goes to the chat as the record, and to the top of the screen like a receipt handed over at a counter */
    private static void say(Player player, String line) { say(player, line, null); }

    private static void say(Player player, String line, String top) {
        player.sendMessage(line);
        player.sendActionBar(top != null ? top : cn.nukkit.utils.TextFormat.clean(line).replaceFirst("^\[[^\]]*\]\s*", ""));
    }

    private String money(long value) { return String.format("%,d", value) + " " + messages.text("shop.unit"); }

    private void run(Runnable task) {
        Server.getInstance().getScheduler().scheduleTask(plugin, task);
    }
}
