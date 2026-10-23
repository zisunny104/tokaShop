package dev.toka.shop;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Config;
import dev.toka.i18n.Messages;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The shop: a catalogue of what can be bought and sold, prices the server can change, and the ways to trade (command, window, signs). */
public final class Main extends PluginBase {
    private Messages messages;
    private Money money;
    private Trade trade;
    private List<Product> shipped;
    private volatile Catalogue catalogue;
    private String language;
    private int windowTax, signTax, maxPacks, perPage;
    private ShopWindow window;
    private SignLink signs;
    private UUID taxAccount;

    @Override public void onEnable() {
        saveDefaultConfig();
        try (var in = getResource("catalogue.json")) {
            shipped = Catalogue.read(in);
        } catch (IOException | RuntimeException error) {
            getLogger().error("商品目錄讀取失敗，商店停用：" + error);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        load();
        trade = new Trade(this, money, messages);
        validateItems();
        window = new ShopWindow(this);
        getServer().getPluginManager().registerEvents(window, this);
        getServer().getPluginManager().registerEvents(new cn.nukkit.event.Listener() {
            @cn.nukkit.event.EventHandler public void on(cn.nukkit.event.plugin.PluginEnableEvent e) {
                if (e.getPlugin().getName().equals("tokaSigns")) getServer().getScheduler().scheduleTask(Main.this, Main.this::linkSigns);
            }
        }, this);
        linkSigns();
    }

    @Override public void onDisable() {
        if (signs != null) signs.unregister();
    }

    private void linkSigns() {
        if (signs == null) {
            Map<String, String> barcodes = new HashMap<>();
            try (var in = getResource("barcodes.json")) {
                new com.google.gson.Gson().fromJson(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8), com.google.gson.JsonObject.class)
                        .entrySet().forEach(e -> barcodes.put(e.getKey(), e.getValue().getAsString()));
            } catch (IOException | RuntimeException error) { getLogger().warning("舊條碼對照讀取失敗：" + error); }
            signs = new SignLink(this, barcodes);
        }
        if (signs.register()) getLogger().info("商店告示牌（購買、回收、物品管理）已接上 tokaSigns");
    }

    void openWindow(Player player) { window.open(player); }

    /** Reads the config and the server's own changes again */
    private int load() {
        reloadConfig();
        var config = getConfig();
        language = config.getString("language", "zh-TW");
        messages = new Messages(getClass().getClassLoader(), getDataFolder().toPath(), language, line -> getLogger().warning(line));
        money = new Money(config.getString("currency", "money"));
        windowTax = Math.max(0, config.getInt("tax.window", 5));
        signTax = Math.max(0, config.getInt("tax.sign", 0));
        maxPacks = Math.max(1, config.getInt("window.max-packs", 36));
        perPage = Math.max(5, config.getInt("window.per-page", 30));
        String account = config.getString("tax.account", "");
        try { taxAccount = account.isBlank() ? null : UUID.fromString(account.trim()); }
        catch (IllegalArgumentException bad) { taxAccount = null; getLogger().warning("tax.account 不是有效的 UUID，稅金不存入帳戶"); }
        Map<String, Catalogue.Override> changes = readOverrides();
        catalogue = Catalogue.with(shipped, changes, language);
        if (trade != null) trade = new Trade(this, money, messages);
        return changes.size();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Catalogue.Override> readOverrides() {
        File file = new File(getDataFolder(), "overrides.yml");
        if (!file.exists()) {
            try {
                getDataFolder().mkdirs();
                java.nio.file.Files.writeString(file.toPath(), "# 個別品項的調整，以商品代碼為鍵。buy、sell 是每組的價格（-1 為關閉）；name 改顯示名稱；disabled: true 整個停用\n"
                        + "# 例：\n# items:\n#   diamond:\n#     buy: 2500\n#     sell: 1200\n#     name: 鑽石\nitems: {}\n");
            } catch (IOException ignored) { }
        }
        Map<String, Catalogue.Override> out = new HashMap<>();
        Object section = new Config(file, Config.YAML).get("items");
        if (!(section instanceof Map<?, ?> items)) return out;
        for (Map.Entry<?, ?> e : items.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> v)) continue;
            out.put(String.valueOf(e.getKey()), new Catalogue.Override(number(v.get("buy")), number(v.get("sell")),
                    v.get("name") == null ? null : String.valueOf(v.get("name")), Boolean.TRUE.equals(v.get("disabled"))));
        }
        return out;
    }

    private static Long number(Object o) { return o instanceof Number n ? n.longValue() : null; }

    /** A product whose id this server does not know cannot be traded: it is closed, and counted once in the log. */
    private void validateItems() {
        int unknown = 0;
        List<Product> kept = new java.util.ArrayList<>();
        for (Product p : shipped) {
            if (Trade.item(p, 1) == null) { unknown++; kept.add(p.withPrices(-1, -1)); } else kept.add(p);
        }
        shipped = kept;
        catalogue = Catalogue.with(shipped, readOverrides(), language);
        long open = catalogue.all().stream().filter(p -> p.canBuy() || p.canSell()).count();
        getLogger().info("商店：" + shipped.size() + " 個品項，" + open + " 個開放" + (unknown > 0 ? "，" + unknown + " 個此伺服器不認得而關閉" : ""));
    }

    String language() { return language; }

    Messages messages() { return messages; }

    int windowTax() { return windowTax; }

    int maxPacks() { return maxPacks; }

    int perPage() { return perPage; }

    Catalogue catalogue() { return catalogue; }

    long balanceOf(UUID id) {
        try {
            Class<?> economy = Class.forName("dev.toka.economy.Economy");
            Object r = economy.getMethod("balance", UUID.class, String.class).invoke(null, id, getConfig().getString("currency", "money"));
            return (Long) r.getClass().getMethod("balance").invoke(r);
        } catch (ReflectiveOperationException | LinkageError unknown) { return 0; }
    }

    /** Tax goes to the configured account; with none set it is simply taken out of circulation */
    void payTax(long tax, String operation) {
        UUID account = taxAccount != null ? taxAccount : treasury();
        if (account != null) money.deposit(account, tax, "tax", operation + ":tax");
        else getLogger().warning("找不到主國庫，這筆消費稅 " + tax + " 沒有存入任何帳戶");
    }

    /** The main treasury of tokaRegions, when that plugin is there and one region holds it */
    private UUID treasury() {
        try {
            Object id = Class.forName("dev.toka.regions.Main").getMethod("treasuryAccount").invoke(null);
            return id == null ? null : UUID.fromString(String.valueOf(id));
        } catch (ReflectiveOperationException | LinkageError | IllegalArgumentException absent) { return null; }
    }

    /** Buying and selling for other plugins (signs, windows) */
    public void buy(Player player, String key, int pieces, boolean sign) {
        Product p = catalogue.get(key);
        if (p == null) { player.sendMessage(messages.text("shop.unknown", key)); return; }
        trade.buy(player, p, pieces, sign ? signTax : windowTax);
    }

    public void sell(Player player, String key, int pieces) {
        Product p = catalogue.get(key);
        if (p == null) { player.sendMessage(messages.text("shop.unknown", key)); return; }
        trade.sell(player, p, pieces);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) window.open(player); else sender.sendMessage(messages.text("shop.usage"));
            return true;
        }
        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "price" -> {
                Product p = args.length > 1 ? catalogue.get(args[1]) : null;
                if (p == null) { sender.sendMessage(messages.text("shop.unknown", args.length > 1 ? args[1] : "")); return true; }
                sender.sendMessage(messages.text("shop.price_line", p.name(language), p.pack(), price(p.buy()), price(p.sell())));
            }
            case "buy", "sell" -> {
                if (!(sender instanceof Player player)) return true;
                Product p = args.length > 1 ? catalogue.get(args[1]) : null;
                if (p == null) { sender.sendMessage(messages.text("shop.unknown", args.length > 1 ? args[1] : "")); return true; }
                int pieces = p.pack();
                if (args.length > 2) {
                    try { pieces = Integer.parseInt(args[2]); } catch (NumberFormatException bad) { pieces = 0; }
                }
                if (pieces < 1 || pieces > maxPacks * p.pack()) { sender.sendMessage(messages.text("shop.bad_count", maxPacks * p.pack())); return true; }
                if (sub.equals("buy")) trade.buy(player, p, pieces, windowTax); else trade.sell(player, p, pieces);
            }
            case "reload" -> {
                if (!sender.hasPermission("tokashop.admin")) { sender.sendMessage(messages.text("shop.admin_only")); return true; }
                int changes = load();
                sender.sendMessage(messages.text("shop.reloaded", catalogue.all().size(), changes));
            }
            case "set", "enable", "disable" -> sender.sendMessage(messages.text(sender.hasPermission("tokashop.admin") ? "shop.usage" : "shop.admin_only"));
            default -> sender.sendMessage(messages.text("shop.usage"));
        }
        return true;
    }

    private String price(long value) { return value > 0 ? String.valueOf(value) : messages.text("shop.closed"); }
}
