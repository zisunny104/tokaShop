package dev.toka.shop;

import cn.nukkit.Player;
import cn.nukkit.blockentity.BlockEntitySign;
import cn.nukkit.item.Item;
import com.google.gson.JsonObject;
import dev.toka.storage.Backend;
import dev.toka.storage.DocumentStore;
import dev.toka.storage.StoreConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The personal shops as a service: the stalls in memory, their documents on disk (written on one background thread, in order), whether the
 * claim under a sign still belongs to the owner, and buying. Money is paid by tokaEconomy with operation ids built from a sale id, so a retried call
 * never pays twice; the sale id also keeps a payout that failed in the stall until it goes through.
 */
final class Stalls {
    private final Main plugin;
    private final LandLink land;
    private final DocumentStore store;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "tokaShop-writer");
        thread.setDaemon(true);
        return thread;
    });
    private final StallBook book;
    private final Set<String> paying = new HashSet<>();

    Stalls(Main plugin) throws IOException {
        this.plugin = plugin;
        this.land = new LandLink(plugin);
        var config = plugin.getConfig();
        Path folder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path directory = folder.resolve(config.getString("storage.directory", "stalls")).normalize();
        if (!directory.startsWith(folder)) throw new IllegalArgumentException("storage.directory must remain inside the plugin data folder");
        Backend backend = Backend.valueOf(config.getString("storage.backend", "SQLITE").strip().toUpperCase(Locale.ROOT));
        store = DocumentStore.open(new StoreConfig(backend, directory, 1));
        book = new StallBook(new StallBook.Persist() {
            @Override public void save(String key, JsonObject document) { write(() -> store.save(key, document), key); }

            @Override public void delete(String key) { write(() -> store.delete(key), key); }
        });
        int skipped = 0;
        for (Map.Entry<String, JsonObject> e : store.snapshot().entrySet()) {
            Stall stall = Stall.fromJson(e.getKey(), e.getValue());
            if (stall == null || !book.load(stall)) skipped++;
        }
        book.namesChanged = plugin::namesChanged;
        plugin.getLogger().info(plugin.messages().text("log.stall_count", book.size()));
        if (skipped > 0) plugin.getLogger().warning(plugin.messages().text("log.stall_skipped", skipped));
    }

    private interface Write { void run() throws IOException; }

    private void write(Write job, String key) {
        try {
            writer.execute(() -> {
                try { job.run(); } catch (IOException | RuntimeException failed) { plugin.getLogger().error(plugin.messages().text("log.stall_write_failed", key, String.valueOf(failed))); }
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) { plugin.getLogger().error(plugin.messages().text("log.stall_write_failed", key, "closed")); }
    }

    /** Lets the queued writes finish, then closes the store */
    void close() {
        writer.shutdown();
        try { writer.awaitTermination(30, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        try { store.close(); } catch (IOException failed) { plugin.getLogger().error(plugin.messages().text("log.stall_write_failed", "close", String.valueOf(failed))); }
    }

    StallBook book() { return book; }

    LandLink land() { return land; }

    int maxPerPlayer() { return Math.max(1, plugin.getConfig().getInt("stall.max-per-player", 3)); }

    int maxProducts() { return Math.max(1, plugin.getConfig().getInt("stall.max-products", 27)); }

    long maxPrice() { return Math.max(1, plugin.getConfig().getLong("stall.max-price", 1_000_000)); }

    /** The stall tax in percent; -1 in the config follows the window tax of the catalogue shop */
    int tax() {
        int set = plugin.getConfig().getInt("stall.tax", -1);
        return set < 0 ? plugin.windowTax() : set;
    }

    String signTag() { return StallRules.cleanName(plugin.getConfig().getString("stall.sign-tag", "[stall]")); }

    boolean selling(Stall stall) { return stall.selling(land.installed()); }

    /** The state as a lang key suffix: open, closed, locked, land */
    String state(Stall stall) {
        if (stall.locked) return "locked";
        if (stall.signed && !stall.landOk && land.installed()) return "land";
        return stall.open ? "open" : "closed";
    }

    // ---- the claim under the sign -----------------------------------------------------------

    /** Looks again at whether the claim under the stall's sign still belongs to the owner; closes the stall when not */
    void recheck(Stall stall) {
        if (!stall.signed || !land.installed()) return;
        Player owner = plugin.getServer().getOnlinePlayers().get(stall.owner);
        boolean ok = stall.landOk;
        java.util.List<String> claims = stall.claims;
        if (owner != null) {
            LandLink.Standing now = land.manage(owner, stall.world, stall.x, stall.y, stall.z);
            if (!now.known()) return;
            ok = now.allowed();
            if (ok) claims = now.claims();
        } else {
            // the owner is away: the claims under the sign can only be compared with what they were; a change closes the stall, never reopens it
            java.util.List<String> here = land.claimsAt(stall.world, stall.x, stall.y, stall.z);
            if (here == null) return;
            if (stall.landOk && !new HashSet<>(here).equals(new HashSet<>(stall.claims))) ok = false;
        }
        if (ok == stall.landOk && claims.equals(stall.claims)) return;
        stall.landOk = ok;
        stall.claims = new java.util.ArrayList<>(claims);
        book.save(stall);
        signText(stall);
        plugin.getLogger().info(plugin.messages().text(ok ? "log.stall_land_back" : "log.stall_land_lost", stall.name, stall.ownerName));
        if (owner != null && !ok) owner.sendMessage(plugin.messages().text("stall.land_lost", stall.name));
    }

    /** The owner is here: check their stalls and pay out what is still owed */
    void arrived(Player owner) {
        for (Stall stall : book.of(owner.getUniqueId())) {
            recheck(stall);
            settle(stall);
        }
    }

    /** Writes the stall on its sign, if the sign is in a loaded chunk */
    void signText(Stall stall) {
        if (!stall.signed) return;
        var level = plugin.getServer().getLevelByName(stall.world);
        if (level == null) return;
        if (level.getBlockEntity(new cn.nukkit.math.Vector3(stall.x, stall.y, stall.z)) instanceof BlockEntitySign sign) sign.setText(lines(stall));
    }

    String[] lines(Stall stall) {
        var m = plugin.messages();
        return new String[]{stall.name, stall.ownerName, m.text("stall.sign_" + state(stall)), m.text("stall.sign_click")};
    }

    // ---- buying ---------------------------------------------------------------------------

    /** The lang key of what stops this purchase now, or null when it can go ahead */
    private String problem(Player buyer, Stall stall, String key, int pieces, long seenPrice) {
        if (book.byId(stall.id) != stall || !selling(stall)) return "stall.closed_now";
        Stock.Line line = stall.stock.find(key);
        if (line == null || line.count() < pieces) return "stall.short";
        if (line.price() != seenPrice) return "stall.price_changed";
        Item item = StallItems.make(line, pieces);
        if (item == null) return "shop.unknown";
        if (!buyer.getInventory().canAddItem(item)) return "shop.no_space";
        return null;
    }

    private void say(Player player, String key, Object... args) { plugin.say(player, plugin.messages().text(key, args)); }

    /** Pieces are checked and the money taken first; stock goes down and the items arrive only after the payment succeeded */
    void buy(Player buyer, Stall stall, String key, int pieces, long seenPrice) {
        var m = plugin.messages();
        if (!plugin.money().present()) { say(buyer, "shop.no_economy"); return; }
        if (buyer.getUniqueId().equals(stall.owner)) { say(buyer, "stall.own_stall"); return; }
        Stock.Line line = stall.stock.find(key);
        String why = pieces < 1 || line == null ? "stall.short" : problem(buyer, stall, key, pieces, seenPrice);
        if (why != null) {
            if (why.equals("shop.no_space")) say(buyer, why, line.label(), pieces); else say(buyer, why, stall.name);
            return;
        }
        long total = StallRules.total(seenPrice, pieces);
        if (total < 1) { say(buyer, "shop.failed", "AMOUNT"); return; }
        long tax = Pricing.tax(total, tax());
        String sale = UUID.randomUUID().toString();
        String operation = "stall-buy:" + sale;
        UUID id = buyer.getUniqueId();
        plugin.money().withdraw(id, total + tax, "stall:" + stall.id, operation).thenAccept(paid -> plugin.run(() -> {
            if (!paid.ok()) {
                if (paid.status().equals("INSUFFICIENT_FUNDS")) say(buyer, "shop.broke", plugin.amount(total + tax), plugin.amount(plugin.balanceOf(id)));
                else say(buyer, "shop.failed", paid.status());
                return;
            }
            String late = buyer.isOnline() ? problem(buyer, stall, key, pieces, seenPrice) : "offline";
            if (late != null) {
                refund(buyer, total + tax, operation);
                say(buyer, "stall.refunded", stall.name);
                return;
            }
            Stock.Line now = stall.stock.find(key);
            Item item = StallItems.make(now, pieces);
            stall.stock.take(key, pieces);
            stall.owed.put(sale, total);
            book.save(stall);
            for (Item rest : buyer.getInventory().addItem(item)) buyer.getLevel().dropItem(buyer, rest);
            if (tax > 0) plugin.payTax(tax, operation);
            String taxPart = tax > 0 ? m.text("shop.bought_tax", plugin.amount(tax)) : "";
            say(buyer, "stall.bought", now.label(), pieces, plugin.amount(total + tax), taxPart, plugin.amount(paid.balance()));
            plugin.getLogger().info(m.text("log.stall_sale", stall.name, buyer.getName(), now.label(), pieces, plugin.amount(total)));
            payout(stall, sale, total, buyer.getName(), now.label(), pieces);
        }));
    }

    private void refund(Player buyer, long amount, String operation) {
        plugin.money().deposit(buyer.getUniqueId(), amount, "stall-refund", operation + ":refund").thenAccept(back -> {
            if (!back.ok()) plugin.getLogger().error(plugin.messages().text("log.stall_refund_failed", buyer.getName(), amount, operation));
        });
    }

    /** Pays the owner of one sale; while it has not gone through it stays in the stall's owed list and is tried again later */
    private void payout(Stall stall, String sale, long amount, String buyerName, String label, int pieces) {
        if (!paying.add(sale)) return;
        plugin.money().deposit(stall.owner, amount, "stall-sale:" + stall.id, "stall-sale:" + sale).thenAccept(paid -> plugin.run(() -> {
            paying.remove(sale);
            if (!paid.ok()) {
                plugin.getLogger().warning(plugin.messages().text("log.stall_payout_failed", stall.name, amount, paid.status()));
                return;
            }
            if (stall.owed.remove(sale) == null) return;
            stall.income += amount;
            if (book.byId(stall.id) == stall) book.save(stall);
            Player owner = plugin.getServer().getOnlinePlayers().get(stall.owner);
            if (owner != null && buyerName != null) say(owner, "stall.sold", stall.name, buyerName, label, pieces, plugin.amount(amount));
        }));
    }

    /** Tries again every sale of the stall that is still owed */
    void settle(Stall stall) {
        for (Map.Entry<String, Long> e : Map.copyOf(stall.owed).entrySet()) payout(stall, e.getKey(), e.getValue(), null, null, 0);
    }

    void settleAll() { book.all().forEach(this::settle); }
}
