package dev.toka.shop;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementButtonImageData;
import cn.nukkit.form.element.ElementDropdown;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementSlider;
import cn.nukkit.form.response.FormResponse;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseModal;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowModal;
import cn.nukkit.form.window.FormWindowSimple;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * The shop window: six categories, their sections, the families of a section (paged, searchable), one family with its variants, and a
 * confirmation that shows the total, the tax and the balance before any money moves.
 */
final class ShopWindow implements Listener {
    private record Entry(Player player, Consumer<FormResponse> handler) {}

    private final Main plugin;
    private final Map<FormWindow, Entry> open = Collections.synchronizedMap(new WeakHashMap<>());

    private AdminWindow admin;

    ShopWindow(Main plugin) { this.plugin = plugin; }

    void admin(AdminWindow admin) { this.admin = admin; }

    String text(String key, Object... args) { return plugin.messages().text(key, args); }

    void show(Player player, FormWindow window, Consumer<FormResponse> handler) {
        open.put(window, new Entry(player, handler));
        player.showFormWindow(window);
    }

    static final String BACK = "textures/ui/arrow_left", NEXT = "textures/ui/arrow_right", SEARCH = "textures/ui/magnifyingGlass", CLOSE = "textures/ui/cancel";

    static ElementButton button(String label, String icon) {
        return icon == null ? new ElementButton(label) : new ElementButton(label, new ElementButtonImageData("path", icon));
    }

    /** A simple window whose buttons run the given actions in order */
    void menu(Player player, String title, String body, List<ElementButton> buttons, List<Runnable> actions) {
        var window = new FormWindowSimple(title, body);
        buttons.forEach(window::addButton);
        show(player, window, r -> {
            if (r instanceof FormResponseSimple s && s.getClickedButtonId() >= 0 && s.getClickedButtonId() < actions.size()) actions.get(s.getClickedButtonId()).run();
        });
    }

    void open(Player player) {
        List<ElementButton> buttons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (var category : Catalogue.LAYOUT.entrySet()) {
            String icon = categoryIcon(category.getKey());
            if (icon == null) for (String section : category.getValue()) {
                var families = plugin.catalogue().families(section);
                if (!families.isEmpty() && families.get(0).first().icon() != null) { icon = families.get(0).first().icon(); break; }
            }
            buttons.add(button(category.getKey(), icon));
            actions.add(() -> sections(player, category.getKey()));
        }
        buttons.add(button(text("window.search"), SEARCH));
        actions.add(() -> search(player, ""));
        if (player.hasPermission("tokashop.admin")) {
            buttons.add(button(text("window.admin"), "textures/ui/settings_glyph_color_2x"));
            actions.add(() -> admin.open(player));
        }
        buttons.add(button(text("window.close"), CLOSE));
        actions.add(() -> { });
        menu(player, text("window.title"), text("window.pick_category"), buttons, actions);
    }

    /** The picture of a top category: the one in config window.icons.<category> (empty for none), else the hand-picked default */
    private String categoryIcon(String category) {
        String set = plugin.getConfig().getString("window.icons." + category, null);
        if (set != null) return set.isBlank() ? null : set;
        return Catalogue.ICONS.get(category);
    }

    private void sections(Player player, String category) {
        List<String> sections = Catalogue.LAYOUT.get(category);
        if (sections.size() == 1) { families(player, category, sections.get(0), 0); return; }
        List<ElementButton> buttons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (String section : sections) {
            var list = plugin.catalogue().families(section);
            buttons.add(button(section, list.isEmpty() ? null : list.get(0).first().icon()));
            actions.add(() -> families(player, category, section, 0));
        }
        buttons.add(button(text("window.back"), BACK));
        actions.add(() -> open(player));
        menu(player, category, text("window.pick_section"), buttons, actions);
    }

    private void families(Player player, String category, String section, int page) {
        List<Catalogue.Family> all = plugin.catalogue().families(section).stream().filter(f -> f.members().stream().anyMatch(p -> p.canBuy() || p.canSell())).toList();
        int per = plugin.perPage();
        int pages = Paging.pages(all.size(), per);
        int current = Paging.clamp(page, pages);
        List<ElementButton> buttons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (var family : all.subList(Paging.from(current, per), Paging.to(current, per, all.size()))) {
            buttons.add(button(label(family), family.first().icon()));
            actions.add(() -> item(player, family, () -> families(player, category, section, current)));
        }
        if (current > 0) { buttons.add(button(text("window.prev"), BACK)); actions.add(() -> families(player, category, section, current - 1)); }
        if (current < pages - 1) { buttons.add(button(text("window.next"), NEXT)); actions.add(() -> families(player, category, section, current + 1)); }
        buttons.add(button(text("window.back"), BACK));
        actions.add(() -> { if (Catalogue.LAYOUT.get(category).size() == 1) open(player); else sections(player, category); });
        menu(player, section, text("window.list_text", all.size(), current + 1, pages), buttons, actions);
    }

    private String label(Catalogue.Family family) {
        String name = family.first().familyName(plugin.language());
        return family.members().size() > 1 ? text("window.family_label", name, family.members().size()) : name;
    }

    private void search(Player player, String word) {
        var window = new FormWindowCustom(text("window.search"));
        window.addElement(new ElementInput(text("window.search_hint"), "", word));
        show(player, window, r -> {
            if (!(r instanceof FormResponseCustom c)) return;
            String entered = c.getInputResponse(0) == null ? "" : c.getInputResponse(0).trim();
            if (entered.isEmpty()) { open(player); return; }
            results(player, entered, 0);
        });
    }

    private void results(Player player, String word, int page) {
        var found = plugin.catalogue().search(word, 200).stream().filter(p -> p.canBuy() || p.canSell()).toList();
        // matches are shown family by family, as everywhere else
        var grouped = new java.util.LinkedHashMap<String, List<Product>>();
        for (Product p : found) grouped.computeIfAbsent(p.section() + "|" + p.family(), k -> new ArrayList<>()).add(p);
        List<Catalogue.Family> families = grouped.values().stream().map(m -> new Catalogue.Family(m.get(0).family(), m.get(0).section(), m)).toList();
        int per = plugin.perPage();
        int pages = Paging.pages(families.size(), per);
        int current = Paging.clamp(page, pages);
        List<ElementButton> buttons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (var family : families.subList(Paging.from(current, per), Paging.to(current, per, families.size()))) {
            buttons.add(button(label(family), family.first().icon()));
            actions.add(() -> item(player, family, () -> results(player, word, current)));
        }
        if (current > 0) { buttons.add(button(text("window.prev"), BACK)); actions.add(() -> results(player, word, current - 1)); }
        if (current < pages - 1) { buttons.add(button(text("window.next"), NEXT)); actions.add(() -> results(player, word, current + 1)); }
        buttons.add(button(text("window.search"), SEARCH));
        actions.add(() -> search(player, word));
        buttons.add(button(text("window.back"), BACK));
        actions.add(() -> open(player));
        menu(player, text("window.search"), families.isEmpty() ? text("window.no_match", word) : text("window.list_text", families.size(), current + 1, pages), buttons, actions);
    }

    /** One family: which variant, buy or sell, how many packs */
    private void item(Player player, Catalogue.Family family, Runnable back) {
        List<Product> members = family.members().stream().filter(p -> p.canBuy() || p.canSell()).toList();
        if (members.isEmpty()) { back.run(); return; }
        var window = new FormWindowCustom(family.first().familyName(plugin.language()));
        Product first = members.get(0);
        window.addElement(new ElementLabel(text("window.item_text", first.pack(), plugin.windowTax())));
        if (members.size() > 1) {
            List<String> names = new ArrayList<>();
            for (Product p : members) names.add(text("window.variant_line", variant(p), price(p.buy()), price(p.sell())));
            window.addElement(new ElementDropdown(text("window.variant"), names, 0));
        } else {
            window.addElement(new ElementLabel(text("window.variant_line", first.name(plugin.language()), price(first.buy()), price(first.sell()))));
        }
        // only sides that are open for some variant are offered; buying comes first
        boolean anyBuy = members.stream().anyMatch(Product::canBuy), anySell = members.stream().anyMatch(Product::canSell);
        List<String> sides = new ArrayList<>();
        if (anyBuy) sides.add(text("window.buy"));
        if (anySell) sides.add(text("window.sell"));
        if (sides.size() > 1) window.addElement(new ElementDropdown(text("window.action"), sides, 0));
        else window.addElement(new ElementLabel(text("window.action") + "：" + sides.get(0)));
        int most = plugin.maxPacks();
        if (most > 1) window.addElement(new ElementSlider(text("window.packs", most), 1, most, 1, 1));
        else window.addElement(new ElementLabel(text("window.packs", most)));
        show(player, window, r -> {
            if (!(r instanceof FormResponseCustom c)) return;
            Product chosen = members.size() > 1 ? members.get(c.getDropdownResponse(1).getElementID()) : first;
            boolean buying = sides.size() > 1 ? (anyBuy && c.getDropdownResponse(2).getElementID() == 0) : anyBuy;
            int packs = most > 1 ? Math.round(c.getSliderResponse(3)) : 1;
            if (packs < 1 || packs > most) { player.sendMessage(text("shop.bad_count", most)); item(player, family, back); return; }
            confirm(player, family, chosen, buying, packs, back);
        });
    }

    private String variant(Product p) { return p.variant().isEmpty() ? p.name(plugin.language()) : p.variant(); }

    private String price(long value) { return value > 0 ? String.valueOf(value) : text("shop.closed"); }

    private void confirm(Player player, Catalogue.Family family, Product product, boolean buying, int packs, Runnable back) {
        if (buying ? !product.canBuy() : !product.canSell()) {
            player.sendMessage(text(buying ? "shop.closed_buy" : "shop.closed_sell", product.name(plugin.language())));
            item(player, family, back);
            return;
        }
        int pieces = packs * product.pack();
        long total = buying ? Pricing.buyTotal(product.buy(), product.pack(), pieces) : Pricing.sellTotal(product.sell(), product.pack(), pieces);
        long tax = buying ? Pricing.tax(total, plugin.windowTax()) : 0;
        String body = text(buying ? "window.confirm_buy" : "window.confirm_sell", product.name(plugin.language()), pieces, total, tax, total + tax, plugin.balanceOf(player.getUniqueId()));
        var window = new FormWindowModal(text("window.confirm_title"), body, text("window.confirm"), text("window.cancel"));
        show(player, window, r -> {
            if (!(r instanceof FormResponseModal m)) return;
            if (m.getClickedButtonId() != 0) { item(player, family, back); return; }
            if (buying) plugin.buy(player, product.key(), pieces, false); else plugin.sell(player, product.key(), pieces);
        });
    }

    @EventHandler public void onResponse(PlayerFormRespondedEvent event) {
        Entry entry = open.remove(event.getWindow());
        if (entry == null || entry.player() != event.getPlayer() || event.wasClosed() || event.getResponse() == null) return;
        entry.handler().accept(event.getResponse());
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        synchronized (open) { open.values().removeIf(entry -> entry.player() == event.getPlayer()); }
    }
}
