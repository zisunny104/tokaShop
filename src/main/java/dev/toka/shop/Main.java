package dev.toka.shop;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.CommandEnum;
import cn.nukkit.command.data.CommandParamType;
import cn.nukkit.command.data.CommandParameter;
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
    private AdminWindow admin;
    private Map<String, Catalogue.Override> overrides = Map.of();
    private SignLink signs;
    private UUID taxAccount;

    @Override public void onEnable() {
        saveDefaultConfig();
        readSettings();
        try (var in = getResource("catalogue.json")) {
            shipped = Catalogue.read(in);
        } catch (IOException | RuntimeException error) {
            getLogger().error(messages.text("log.catalogue_failed", String.valueOf(error)));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        trade = new Trade(this);
        validateItems();
        window = new ShopWindow(this);
        admin = new AdminWindow(this, window);
        window.admin(admin);
        getServer().getPluginManager().registerEvents(window, this);
        getServer().getPluginManager().registerEvents(new cn.nukkit.event.Listener() {
            @cn.nukkit.event.EventHandler public void on(cn.nukkit.event.plugin.PluginEnableEvent e) {
                if (e.getPlugin().getName().equals("tokaSigns")) getServer().getScheduler().scheduleTask(Main.this, Main.this::linkSigns);
            }
        }, this);
        linkSigns();
        suggestCommands();
    }

    @Override public void onDisable() {
        if (signs != null) signs.unregister();
        signs = null;
    }

    /** Tab completion: Bedrock command overloads. Enum names differ per overload because one name must keep one set of words. */
    private void suggestCommands() {
        var command = getCommand("shop");
        if (command == null) return;
        Command registered = (Command) command;
        registered.getCommandParameters().clear();
        CommandParameter code = CommandParameter.newType("code", CommandParamType.STRING);
        registered.addCommandParameters("trade", new CommandParameter[]{CommandParameter.newEnum("action", new CommandEnum("TokaShopTrade", "buy", "sell")), code,
                CommandParameter.newType("pieces", true, CommandParamType.INT)});
        registered.addCommandParameters("price", new CommandParameter[]{CommandParameter.newEnum("action", new CommandEnum("TokaShopPrice", "price")), code});
        registered.addCommandParameters("help", new CommandParameter[]{CommandParameter.newEnum("action", new CommandEnum("TokaShopHelp", "help")),
                CommandParameter.newType("page", true, CommandParamType.INT)});
        registered.addCommandParameters("plain", new CommandParameter[]{CommandParameter.newEnum("action", new CommandEnum("TokaShopPlain", "reload", "admin"))});
        registered.addCommandParameters("toggle", new CommandParameter[]{CommandParameter.newEnum("action", new CommandEnum("TokaShopToggle", "enable", "disable")), code});
        registered.addCommandParameters("set", new CommandParameter[]{CommandParameter.newEnum("action", new CommandEnum("TokaShopSet", "set")), code,
                CommandParameter.newType("buy", CommandParamType.INT), CommandParameter.newType("sell", CommandParamType.INT)});
        // the client keeps the command list it got at join, so it is sent again after a (re)load
        getServer().getScheduler().scheduleTask(this, () -> getServer().getOnlinePlayers().values().forEach(Player::sendCommandData));
    }

    private void linkSigns() {
        if (signs == null) {
            Map<String, String> barcodes = new HashMap<>();
            try (var in = getResource("barcodes.json")) {
                new com.google.gson.Gson().fromJson(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8), com.google.gson.JsonObject.class)
                        .entrySet().forEach(e -> barcodes.put(e.getKey(), e.getValue().getAsString()));
            } catch (IOException | RuntimeException error) { getLogger().warning(messages.text("log.barcodes_failed", String.valueOf(error))); }
            signs = new SignLink(this, barcodes);
        }
        if (signs.register()) getLogger().info(messages.text("log.signs_linked"));
    }

    void openWindow(Player player) { window.open(player); }

    /** Reads the config again: language, messages, currency, taxes, window sizes */
    private void readSettings() {
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
        catch (IllegalArgumentException bad) { taxAccount = null; getLogger().warning(messages.text("log.tax_account_bad")); }
    }

    /** Settings and the server's own changes again. Trade, window and signs ask the plugin for messages, money and catalogue each time, so they follow. */
    private int load() {
        readSettings();
        overrides = readOverrides();
        catalogue = Catalogue.with(shipped, overrides, language);
        return overrides.size();
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
        overrides = readOverrides();
        catalogue = Catalogue.with(shipped, overrides, language);
        long open = catalogue.all().stream().filter(p -> p.canBuy() || p.canSell()).count();
        getLogger().info(messages.text("log.shop_count", shipped.size(), open));
        if (unknown > 0) getLogger().warning(messages.text("log.shop_unknown", unknown));
    }

    String language() { return language; }

    Money money() { return money; }

    /** A product as it is when enabled, with its own prices even when it is switched off now */
    Product enabledView(Product current) {
        for (Product p : shipped) if (p.key().equals(current.key())) return Catalogue.apply(p, overrides.get(p.key()), language, true);
        return current;
    }

    boolean disabledByOverride(Product p) {
        var o = overrides.get(p.key());
        return o != null && o.disabled();
    }

    Messages messages() { return messages; }

    int windowTax() { return windowTax; }

    int maxPacks() { return maxPacks; }

    int perPage() { return perPage; }

    boolean topNotice() { return getConfig().getBoolean("notice.top", true); }

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
        else getLogger().warning(messages.text("log.no_treasury", tax));
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
                Product p = args.length > 1 ? catalogue.get(args[1].toUpperCase(java.util.Locale.ROOT)) : null;
                if (p == null) { sender.sendMessage(messages.text("shop.unknown", args.length > 1 ? args[1] : "")); return true; }
                sender.sendMessage(messages.text("shop.price_line", p.name(language), p.pack(), price(p.buy()), price(p.sell())));
            }
            case "buy", "sell" -> {
                if (!(sender instanceof Player player)) return true;
                Product p = args.length > 1 ? catalogue.get(args[1].toUpperCase(java.util.Locale.ROOT)) : null;
                if (p == null) { sender.sendMessage(messages.text("shop.unknown", args.length > 1 ? args[1] : "")); return true; }
                int pieces = p.pack();
                if (args.length > 2) {
                    try { pieces = Integer.parseInt(args[2]); } catch (NumberFormatException bad) { pieces = 0; }
                }
                if (pieces < 1 || pieces > maxPacks * p.pack()) { sender.sendMessage(messages.text("shop.bad_count", maxPacks * p.pack())); return true; }
                if (sub.equals("buy")) trade.buy(player, p, pieces, windowTax); else trade.sell(player, p, pieces);
            }
            case "reload" -> reload(sender);
            case "admin" -> {
                if (!(sender instanceof Player player)) return true;
                if (!sender.hasPermission("tokashop.admin")) { sender.sendMessage(messages.text("shop.admin_only")); return true; }
                admin.open(player);
            }
            case "help" -> help(sender, args.length > 1 ? parsePage(args[1]) : 1);
            case "set", "enable", "disable" -> {
                if (!sender.hasPermission("tokashop.admin")) { sender.sendMessage(messages.text("shop.admin_only")); return true; }
                Product p = args.length > 1 ? catalogue.get(args[1].toUpperCase(java.util.Locale.ROOT)) : null;
                if (p == null) { sender.sendMessage(messages.text("shop.unknown", args.length > 1 ? args[1] : "")); return true; }
                if (sub.equals("set")) change(sender, p, args.length > 2 ? args[2] : "", args.length > 3 ? args[3] : "", true);
                else change(sender, p, null, null, sub.equals("enable"));
            }
            default -> sender.sendMessage(messages.text("shop.usage"));
        }
        return true;
    }

    private static int parsePage(String text) {
        try { return Integer.parseInt(text.trim()); } catch (NumberFormatException bad) { return 1; }
    }

    private static final int HELP_PER_PAGE = 5;
    /** Help topics in order; "a" marks the ones only admins may use */
    private static final String[][] HELP = {{"open", ""}, {"buy", ""}, {"sell", ""}, {"price", ""}, {"help", ""}, {"admin", "a"}, {"set", "a"}, {"enable", "a"}, {"disable", "a"},
            {"reload", "a"}, {"permissions", ""}, {"closed", ""}};

    /** Paged help in the chat */
    void help(CommandSender sender, int page) {
        int pages = Paging.pages(HELP.length, HELP_PER_PAGE);
        int current = Paging.clamp(page - 1, pages);
        sender.sendMessage(messages.text("help.header", current + 1, pages));
        for (int i = Paging.from(current, HELP_PER_PAGE); i < Paging.to(current, HELP_PER_PAGE, HELP.length); i++)
            sender.sendMessage(messages.text("help." + HELP[i][0]) + (HELP[i][1].isEmpty() ? "" : messages.text("help.admin_tag")));
    }

    /** Reloads settings, language and overrides; trade, window and signs follow because they read them from here */
    void reload(CommandSender sender) {
        if (!sender.hasPermission("tokashop.admin")) { sender.sendMessage(messages.text("shop.admin_only")); return; }
        int changes = load();
        sender.sendMessage(messages.text("shop.reloaded", catalogue.all().size(), changes));
    }

    /**
     * The one place that writes overrides.yml for /shop set|enable|disable and for the admin window. Prices are texts so both callers
     * validate the same way; null for both leaves the prices as they are. Returns false when nothing was written.
     */
    boolean change(CommandSender sender, Product p, String buyText, String sellText, boolean enabled) {
        if (!sender.hasPermission("tokashop.admin")) { sender.sendMessage(messages.text("shop.admin_only")); return false; }
        boolean setting = buyText != null || sellText != null;
        Long buy = buyText == null ? null : Pricing.parsePrice(buyText), sell = sellText == null ? null : Pricing.parsePrice(sellText);
        if (setting && (buy == null || sell == null)) { sender.sendMessage(messages.text("shop.bad_price")); return false; }
        var config = new Config(new File(getDataFolder(), "overrides.yml"), Config.YAML);
        String base = "items." + p.key();
        if (setting) {
            config.set(base + ".buy", buy);
            config.set(base + ".sell", sell);
        }
        if (enabled) config.remove(base + ".disabled"); else config.set(base + ".disabled", true);
        config.save();
        load();
        sender.sendMessage(messages.text("shop.changed", p.key() + " " + p.name(language)));
        return true;
    }

    private String price(long value) { return value > 0 ? String.valueOf(value) : messages.text("shop.closed"); }
}
