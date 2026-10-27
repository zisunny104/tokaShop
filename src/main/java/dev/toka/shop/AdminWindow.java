package dev.toka.shop;

import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementDivider;
import cn.nukkit.form.element.ElementHeader;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.window.FormWindowCustom;
import java.util.ArrayList;
import java.util.List;

/** Price management for admins: a searchable, paged product list and a form per product. Every write goes through {@link Main#change}. */
final class AdminWindow {
    private final Main plugin;
    private final ShopWindow ui;

    AdminWindow(Main plugin, ShopWindow ui) {
        this.plugin = plugin;
        this.ui = ui;
    }

    private boolean allowed(Player player) {
        if (player.hasPermission("tokashop.admin")) return true;
        player.sendMessage(plugin.messages().text("shop.admin_only"));
        return false;
    }

    void open(Player player) { list(player, "", 0); }

    private void list(Player player, String word, int page) {
        if (!allowed(player)) return;
        List<Product> found = word.isEmpty() ? new ArrayList<>(plugin.catalogue().all()) : plugin.catalogue().search(word, 2000);
        int per = plugin.perPage();
        int pages = Paging.pages(found.size(), per);
        int current = Paging.clamp(page, pages);
        List<ElementButton> buttons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        buttons.add(ui.button(ui.text("window.back"), ShopWindow.BACK));
        actions.add(() -> ui.open(player));
        buttons.add(ui.button(ui.text("window.search"), ShopWindow.SEARCH));
        actions.add(() -> search(player, word));
        buttons.add(ui.button(ui.text("window.reload"), "textures/ui/refresh_light"));
        actions.add(() -> { plugin.reload(player); list(player, word, current); });
        for (Product p : found.subList(Paging.from(current, per), Paging.to(current, per, found.size()))) {
            buttons.add(ui.button(p.key() + " " + p.name(plugin.language()) + "\n"
                    + ui.text("window.admin_line", price(p.buy()), price(p.sell())), p.icon()));
            actions.add(() -> edit(player, p, () -> list(player, word, current)));
        }
        if (current > 0) { buttons.add(ui.button(ui.text("window.prev"), ShopWindow.BACK)); actions.add(() -> list(player, word, current - 1)); }
        if (current < pages - 1) { buttons.add(ui.button(ui.text("window.next"), ShopWindow.NEXT)); actions.add(() -> list(player, word, current + 1)); }
        String body = found.isEmpty() ? ui.text("window.no_match", word) : ui.text("window.list_text", found.size(), current + 1, pages);
        ui.menu(player, ui.text("window.admin_title"), body, buttons, actions);
    }

    private void search(Player player, String word) {
        var window = new FormWindowCustom(ui.text("window.search"));
        window.addElement(new ElementInput(ui.text("window.search_hint"), "", word));
        ui.show(player, window, r -> {
            if (!(r instanceof FormResponseCustom c)) return;
            list(player, c.getInputResponse(0) == null ? "" : c.getInputResponse(0).trim(), 0);
        });
    }

    private String price(long value) { return value > 0 ? String.format("%,d", value) : ui.text("shop.closed"); }

    /** Prices and an enabled switch for one product; a product switched off still shows the prices it has when switched on again */
    private void edit(Player player, Product listed, Runnable back) {
        if (!allowed(player)) return;
        Product p = plugin.enabledView(listed);
        var window = new FormWindowCustom(p.key());
        window.addElement(new ElementHeader(p.name(plugin.language())));
        String names = String.join(" / ", new java.util.TreeMap<>(p.names()).values());
        window.addElement(new ElementLabel(ui.text("window.admin_product", p.key(), p.id() + ":" + p.damage(), names, p.category() + " / " + p.section(), p.pack())));
        window.addElement(new ElementDivider());
        window.addElement(new ElementInput(ui.text("window.admin_buy"), "-1", String.valueOf(p.buy())));
        window.addElement(new ElementInput(ui.text("window.admin_sell"), "-1", String.valueOf(p.sell())));
        window.addElement(new ElementToggle(ui.text("window.admin_enabled"), !plugin.disabledByOverride(p)));
        ui.show(player, window, r -> {
            if (!(r instanceof FormResponseCustom c)) return;
            String buy = c.getInputResponse(3) == null ? "" : c.getInputResponse(3), sell = c.getInputResponse(4) == null ? "" : c.getInputResponse(4);
            if (plugin.change(player, p, buy, sell, c.getToggleResponse(5))) back.run(); else edit(player, listed, back);
        });
    }
}
