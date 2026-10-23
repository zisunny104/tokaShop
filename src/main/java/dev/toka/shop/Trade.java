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
        if (!money.present()) { player.sendMessage(messages.text("shop.no_economy")); return; }
        if (!product.canBuy()) { player.sendMessage(messages.text("shop.closed_buy", name(product))); return; }
        Item item = item(product, pieces);
        if (item == null) { player.sendMessage(messages.text("shop.unknown", product.key())); return; }
        if (!player.getInventory().canAddItem(item)) { player.sendMessage(messages.text("shop.no_space", name(product), pieces)); return; }
        long total = Pricing.buyTotal(product.buy(), product.pack(), pieces);
        long tax = Pricing.tax(total, taxPercent);
        UUID id = player.getUniqueId();
        String operation = "shop-buy:" + UUID.randomUUID();
        money.withdraw(id, total + tax, "buy:" + product.key(), operation).thenAccept(paid -> run(() -> {
            if (!paid.ok()) {
                player.sendMessage(paid.status().equals("INSUFFICIENT_FUNDS") ? messages.text("shop.broke", total + tax, plugin.balanceOf(id)) : messages.text("shop.failed", paid.status()));
                return;
            }
            Item[] left = player.getInventory().addItem(item);
            for (Item rest : left) player.getLevel().dropItem(player, rest);
            if (tax > 0) plugin.payTax(tax, operation);
            player.sendMessage(messages.text("shop.bought", pieces, name(product), total + tax, tax > 0 ? messages.text("shop.bought_tax", tax) : "", paid.balance()));
        }));
    }

    void sell(Player player, Product product, int pieces) {
        if (!money.present()) { player.sendMessage(messages.text("shop.no_economy")); return; }
        if (!product.canSell()) { player.sendMessage(messages.text("shop.closed_sell", name(product))); return; }
        Item wanted = item(product, 1);
        if (wanted == null) { player.sendMessage(messages.text("shop.unknown", product.key())); return; }
        int have = 0;
        for (Item held : player.getInventory().getContents().values()) if (held.equals(wanted, true, true)) have += held.getCount();
        if (have < pieces) { player.sendMessage(messages.text("shop.too_few", name(product), have)); return; }
        Item take = item(product, pieces);
        Item[] failed = player.getInventory().removeItem(take);
        if (failed.length > 0) {
            // a stack changed between the count and the removal: put back what was taken and give up
            int missing = 0;
            for (Item f : failed) missing += f.getCount();
            if (pieces - missing > 0) player.getInventory().addItem(item(product, pieces - missing));
            player.sendMessage(messages.text("shop.too_few", name(product), have - missing));
            return;
        }
        long total = Pricing.sellTotal(product.sell(), product.pack(), pieces);
        money.deposit(player.getUniqueId(), total, "sell:" + product.key(), "shop-sell:" + UUID.randomUUID()).thenAccept(paid -> run(() -> {
            if (!paid.ok()) {
                for (Item rest : player.getInventory().addItem(item(product, pieces))) player.getLevel().dropItem(player, rest);
                player.sendMessage(messages.text("shop.failed", paid.status()));
                return;
            }
            player.sendMessage(messages.text("shop.sold", pieces, name(product), total, paid.balance()));
        }));
    }

    private void run(Runnable task) {
        Server.getInstance().getScheduler().scheduleTask(plugin, task);
    }
}
