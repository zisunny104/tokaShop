package dev.toka.shop;

import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementDivider;
import cn.nukkit.form.element.ElementDropdown;
import cn.nukkit.form.element.ElementHeader;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseModal;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowModal;
import cn.nukkit.item.Item;
import java.util.ArrayList;
import java.util.List;

/**
 * The windows of the personal shops. Visitors: the product list of a stall (search, paging), one product, the amount in stacks and pieces,
 * a confirmation. Owners: their stalls, and per stall the stock, prices, income, open or closed, name and removal. Every owner action looks
 * again at who owns the stall, and every number from a form is parsed and bounded here before anything changes.
 */
final class StallWindow {
    private final Main plugin;
    private final ShopWindow ui;
    private final Stalls stalls;

    StallWindow(Main plugin, ShopWindow ui, Stalls stalls) {
        this.plugin = plugin;
        this.ui = ui;
        this.stalls = stalls;
    }

    private String text(String key, Object... args) { return ui.text(key, args); }

    private String amount(long value) { return plugin.amount(value); }

    private static String input(FormResponseCustom c, int index) {
        try { String s = c.getInputResponse(index); return s == null ? "" : s.trim(); } catch (RuntimeException bad) { return ""; }
    }

    private void tell(Player player, String key, Object... args) { player.sendMessage(text(key, args)); }

    private ElementButton back() { return ui.button(text("window.back"), ShopWindow.BACK); }

    private ElementButton close() { return ui.button("§c" + text("window.close"), ShopWindow.CLOSE); }

    /** The item information shared by every product page: code, name, other names, and whose it is */
    private String info(Stock.Line line, Stall stall) {
        String base = StallItems.baseName(line);
        String other = base.isEmpty() || base.equals(line.label()) ? "-" : base;
        return text("stall.item_info", line.id() + ":" + line.damage(), line.label(), other, stall.name + " / " + stall.ownerName);
    }

    /** Every stall that is selling now, by name */
    void browse(Player player, int page) {
        List<Stall> open = stalls.book().all().stream().filter(stalls::selling).sorted(java.util.Comparator.comparing(s -> s.name.toLowerCase(java.util.Locale.ROOT))).toList();
        int per = plugin.perPage();
        int pages = Paging.pages(open.size(), per);
        int current = Paging.clamp(page, pages);
        List<ElementButton> buttons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        buttons.add(back());
        actions.add(() -> ui.open(player));
        for (Stall stall : open.subList(Paging.from(current, per), Paging.to(current, per, open.size()))) {
            buttons.add(ui.button(text("stall.browse_line", stall.name, stall.ownerName, stall.stock.size()), null));
            actions.add(() -> { if (stall.owner.equals(player.getUniqueId())) manage(player, stall); else visit(player, stall); });
        }
        if (current > 0) { buttons.add(ui.button(text("window.prev"), ShopWindow.BACK)); actions.add(() -> browse(player, current - 1)); }
        if (current < pages - 1) { buttons.add(ui.button(text("window.next"), ShopWindow.NEXT)); actions.add(() -> browse(player, current + 1)); }
        buttons.add(close());
        actions.add(() -> { });
        ui.menu(player, text("stall.browse"), open.isEmpty() ? text("stall.browse_empty") : text("window.list_text", open.size(), current + 1, pages), buttons, actions);
    }

    // ---- visitors -------------------------------------------------------------------------

    void visit(Player player, Stall stall) {
        stalls.recheck(stall);
        if (!stalls.selling(stall)) { tell(player, "stall.closed_now", stall.name); return; }
        list(player, stall, "", 0);
    }

    private void list(Player player, Stall stall, String word, int page) {
        if (!stalls.selling(stall)) { tell(player, "stall.closed_now", stall.name); return; }
        List<Stock.Line> found = stall.stock.search(word);
        int per = plugin.perPage();
        int pages = Paging.pages(found.size(), per);
        int current = Paging.clamp(page, pages);
        List<ElementButton> buttons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        buttons.add(back());
        actions.add(() -> ui.open(player));
        buttons.add(ui.button(text("window.search"), ShopWindow.SEARCH));
        actions.add(() -> search(player, stall, word));
        for (Stock.Line line : found.subList(Paging.from(current, per), Paging.to(current, per, found.size()))) {
            buttons.add(ui.button(text("stall.line_button", line.label(), line.count(), amount(line.price())), null));
            actions.add(() -> product(player, stall, line.key(), () -> list(player, stall, word, current)));
        }
        if (current > 0) { buttons.add(ui.button(text("window.prev"), ShopWindow.BACK)); actions.add(() -> list(player, stall, word, current - 1)); }
        if (current < pages - 1) { buttons.add(ui.button(text("window.next"), ShopWindow.NEXT)); actions.add(() -> list(player, stall, word, current + 1)); }
        buttons.add(close());
        actions.add(() -> { });
        String body = found.isEmpty() ? text(word.isEmpty() ? "stall.empty" : "window.no_match", word) : text("stall.list_text", stall.ownerName, found.size(), current + 1, pages);
        ui.menu(player, stall.name, body, buttons, actions);
    }

    private void search(Player player, Stall stall, String word) {
        var window = new FormWindowCustom(text("window.search"));
        window.addElement(new ElementHeader(stall.name));
        window.addElement(new ElementInput(text("window.search_hint"), "", word));
        ui.show(player, window, r -> {
            if (r instanceof FormResponseCustom c) list(player, stall, input(c, 1), 0);
        });
    }

    private void product(Player player, Stall stall, String key, Runnable back) {
        Stock.Line line = stall.stock.find(key);
        if (line == null || !stalls.selling(stall)) { tell(player, line == null ? "stall.short" : "stall.closed_now", stall.name); back.run(); return; }
        int stack = StallItems.stackSize(line);
        long most = Math.min(line.count(), (long) plugin.maxPacks() * stack);
        var window = new FormWindowCustom(stall.name);
        window.addElement(new ElementHeader(line.label()));
        window.addElement(new ElementLabel(info(line, stall)));
        window.addElement(new ElementDivider());
        window.addElement(new ElementLabel(text("stall.buy_info", amount(line.price()), line.count(), stack, stalls.tax())));
        window.addElement(new ElementInput(text("stall.stacks_box"), "0", "0"));
        window.addElement(new ElementInput(text("stall.pieces_box", most), "1", "1"));
        ui.show(player, window, r -> {
            if (!(r instanceof FormResponseCustom c)) return;
            long pieces = StallRules.pieces(StallRules.parseNumber(input(c, 4), 0, StallRules.STOCK_MAX), StallRules.parseNumber(input(c, 5), 0, StallRules.STOCK_MAX), stack, most);
            if (pieces < 1) { tell(player, "shop.bad_count", most); product(player, stall, key, back); return; }
            confirm(player, stall, key, (int) pieces, back);
        });
    }

    /** N pieces written like a clock: 130 pieces = 2 stacks 2 pieces */
    private String pieces(Stock.Line line, int pieces) {
        int[] split = Stacks.split(pieces, StallItems.stackSize(line));
        if (split[0] == 0) return text("window.pieces_only", pieces);
        return split[1] == 0 ? text("window.pieces_even", pieces, split[0]) : text("window.pieces_full", pieces, split[0], split[1]);
    }

    private void confirm(Player player, Stall stall, String key, int pieces, Runnable back) {
        Stock.Line line = stall.stock.find(key);
        if (line == null || line.count() < pieces || !stalls.selling(stall)) { tell(player, "stall.short", stall.name); back.run(); return; }
        long total = StallRules.total(line.price(), pieces);
        if (total < 1) { tell(player, "shop.bad_count", line.count()); back.run(); return; }
        long tax = Pricing.tax(total, stalls.tax());
        long seen = line.price();
        String body = text("stall.confirm_buy", line.label(), pieces(line, pieces), amount(seen), amount(total), amount(tax), amount(total + tax), amount(plugin.balanceOf(player.getUniqueId())));
        var window = new FormWindowModal(text("window.confirm_title"), body, text("window.confirm"), "§c" + text("window.cancel"));
        ui.show(player, window, r -> {
            if (!(r instanceof FormResponseModal m)) return;
            if (m.getClickedButtonId() != 0) { product(player, stall, key, back); return; }
            stalls.buy(player, stall, key, pieces, seen);
        });
    }

    // ---- owners ---------------------------------------------------------------------------

    /** Whether the player still owns the stall; says so when not */
    private boolean own(Player player, Stall stall) {
        if (stalls.book().byId(stall.id) == stall && stall.owner.equals(player.getUniqueId())) return true;
        tell(player, "stall.not_yours");
        return false;
    }

    private boolean allowed(Player player) {
        if (player.hasPermission("tokashop.stall")) return true;
        tell(player, "stall.no_permission");
        return false;
    }

    void my(Player player) {
        if (!allowed(player)) return;
        stalls.arrived(player);
        List<Stall> mine = stalls.book().of(player.getUniqueId());
        List<ElementButton> buttons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        buttons.add(back());
        actions.add(() -> ui.open(player));
        if (StallRules.canAddOne(mine.size(), stalls.maxPerPlayer())) {
            buttons.add(ui.button(text("stall.create"), "textures/ui/color_plus"));
            actions.add(() -> create(player));
        }
        for (Stall stall : mine) {
            buttons.add(ui.button(text("stall.my_line", stall.name, text("stall.state_" + stalls.state(stall))), null));
            actions.add(() -> manage(player, stall));
        }
        buttons.add(close());
        actions.add(() -> { });
        ui.menu(player, text("stall.my_title"), text("stall.my_text", mine.size(), stalls.maxPerPlayer()), buttons, actions);
    }

    private void create(Player player) {
        if (!allowed(player)) return;
        var window = new FormWindowCustom(text("stall.create"));
        window.addElement(new ElementHeader(text("stall.create")));
        window.addElement(new ElementInput(text("stall.name_box", StallRules.NAME_MAX), "", ""));
        ui.show(player, window, r -> {
            if (!(r instanceof FormResponseCustom c)) return;
            String name = input(c, 1);
            Stall made = stalls.book().create(player.getUniqueId(), player.getName(), name, stalls.maxPerPlayer());
            if (made == null) {
                StallBook.Problem why = stalls.book().check(player.getUniqueId(), name, stalls.maxPerPlayer(), null);
                tell(player, switch (why) { case BAD_NAME -> "stall.bad_name"; case TAKEN -> "stall.name_taken"; default -> "stall.limit"; }, StallRules.NAME_MAX, stalls.maxPerPlayer());
                my(player);
                return;
            }
            plugin.getLogger().info(text("log.stall_created", made.name, made.ownerName));
            tell(player, "stall.created", made.name, stalls.signTag());
            manage(player, made);
        });
    }

    void manage(Player player, Stall stall) {
        if (!own(player, stall)) return;
        String owed = stall.owed.isEmpty() ? "" : text("stall.manage_owed", amount(stall.owed.values().stream().mapToLong(Long::longValue).sum()));
        String sign = stall.signed ? text("stall.manage_sign", stall.world, stall.x, stall.y, stall.z) : text("stall.manage_nosign", stalls.signTag(), stall.name);
        String body = text("stall.manage_text", text("stall.state_" + stalls.state(stall)), stall.stock.size(), stalls.maxProducts(), stall.stock.pieces(), amount(stall.income), owed, sign);
        List<ElementButton> buttons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        buttons.add(back());
        actions.add(() -> my(player));
        buttons.add(ui.button(text("stall.add_stock"), "textures/ui/color_plus"));
        actions.add(() -> addStock(player, stall));
        buttons.add(ui.button(text("stall.products", stall.stock.size()), null));
        actions.add(() -> products(player, stall, 0));
        buttons.add(ui.button(text(stall.open ? "stall.do_close" : "stall.do_open"), null));
        actions.add(() -> toggle(player, stall));
        buttons.add(ui.button(text("stall.rename"), null));
        actions.add(() -> rename(player, stall));
        buttons.add(ui.button("§c" + text("stall.delete"), null));
        actions.add(() -> delete(player, stall));
        buttons.add(close());
        actions.add(() -> { });
        ui.menu(player, stall.name, body, buttons, actions);
    }

    private void toggle(Player player, Stall stall) {
        if (!own(player, stall)) return;
        if (!stall.open && stall.locked) { tell(player, "stall.locked_by_admin", stall.name); manage(player, stall); return; }
        stall.open = !stall.open;
        stalls.book().save(stall);
        stalls.signText(stall);
        tell(player, stall.open ? "stall.opened" : "stall.closed", stall.name);
        manage(player, stall);
    }

    private void rename(Player player, Stall stall) {
        if (!own(player, stall)) return;
        var window = new FormWindowCustom(text("stall.rename"));
        window.addElement(new ElementHeader(stall.name));
        window.addElement(new ElementInput(text("stall.name_box", StallRules.NAME_MAX), "", stall.name));
        ui.show(player, window, r -> {
            if (!(r instanceof FormResponseCustom c) || !own(player, stall)) return;
            String name = input(c, 1);
            if (!stalls.book().rename(stall, name)) {
                StallBook.Problem why = stalls.book().check(stall.owner, name, Integer.MAX_VALUE, stall);
                tell(player, why == StallBook.Problem.TAKEN ? "stall.name_taken" : "stall.bad_name", StallRules.NAME_MAX, stalls.maxPerPlayer());
                rename(player, stall);
                return;
            }
            stalls.signText(stall);
            tell(player, "stall.renamed", stall.name);
            manage(player, stall);
        });
    }

    private void delete(Player player, Stall stall) {
        if (!own(player, stall)) return;
        if (!stall.stock.empty() || !stall.owed.isEmpty()) { tell(player, "stall.delete_not_empty", stall.name); manage(player, stall); return; }
        var window = new FormWindowModal(text("stall.delete"), text("stall.delete_text", stall.name), "§c" + text("stall.delete"), text("window.cancel"));
        ui.show(player, window, r -> {
            if (!(r instanceof FormResponseModal m)) return;
            if (m.getClickedButtonId() != 0 || !own(player, stall)) { manage(player, stall); return; }
            if (!stall.stock.empty() || !stall.owed.isEmpty()) { tell(player, "stall.delete_not_empty", stall.name); manage(player, stall); return; }
            stalls.book().remove(stall);
            plugin.getLogger().info(text("log.stall_removed", stall.name, player.getName()));
            tell(player, "stall.deleted", stall.name);
            my(player);
        });
    }

    private void addStock(Player player, Stall stall) {
        if (!own(player, stall)) return;
        List<StallItems.Held> held = StallItems.carried(player);
        if (held.isEmpty()) { tell(player, "stall.no_items"); manage(player, stall); return; }
        List<String> names = new ArrayList<>();
        for (StallItems.Held h : held) names.add(text("stall.slot_option", h.slot() + 1, StallItems.describe(h.item()).label(), h.item().getCount()));
        var window = new FormWindowCustom(stall.name);
        window.addElement(new ElementHeader(text("stall.add_stock")));
        window.addElement(new ElementLabel(text("stall.add_info", stall.stock.size(), stalls.maxProducts(), amount(stalls.maxPrice()))));
        window.addElement(new ElementDivider());
        window.addElement(new ElementDropdown(text("stall.item_box"), names, 0));
        window.addElement(new ElementInput(text("stall.amount_box"), "1", "1"));
        window.addElement(new ElementInput(text("stall.price_box", amount(stalls.maxPrice())), "10", ""));
        ui.show(player, window, r -> {
            if (!(r instanceof FormResponseCustom c) || !own(player, stall)) return;
            int pick = c.getDropdownResponse(3).getElementID();
            Long pieces = StallRules.parseNumber(input(c, 4), 1, StallRules.STOCK_MAX), price = StallRules.parseNumber(input(c, 5), 1, stalls.maxPrice());
            if (pick < 0 || pick >= held.size()) { addStock(player, stall); return; }
            if (pieces == null) { tell(player, "stall.bad_amount"); addStock(player, stall); return; }
            if (price == null) { tell(player, "stall.bad_price", amount(stalls.maxPrice())); addStock(player, stall); return; }
            int slot = held.get(pick).slot();
            Item there = player.getInventory().getItem(slot);
            Stock.Line template = held.get(pick).item() == null ? null : StallItems.describe(held.get(pick).item());
            // the slot may have changed while the window was open: it must still hold the same item, in the amount asked for
            if (there == null || there.isNull() || !StallItems.describe(there).key().equals(template.key()) || there.getCount() < pieces) {
                tell(player, "stall.slot_changed");
                addStock(player, stall);
                return;
            }
            Stock.Result result = stall.stock.add(template, (int) (long) pieces, price, stalls.maxProducts());
            if (result != Stock.Result.OK) {
                tell(player, result == Stock.Result.FULL ? "stall.products_full" : "stall.stock_full", stalls.maxProducts(), StallRules.STOCK_MAX);
                manage(player, stall);
                return;
            }
            if (there.getCount() == pieces) player.getInventory().clear(slot);
            else { Item rest = there.clone(); rest.setCount(there.getCount() - (int) (long) pieces); player.getInventory().setItem(slot, rest); }
            stalls.book().save(stall);
            tell(player, "stall.stocked", template.label(), pieces, amount(price));
            manage(player, stall);
        });
    }

    private void products(Player player, Stall stall, int page) {
        if (!own(player, stall)) return;
        List<Stock.Line> all = stall.stock.lines();
        int per = plugin.perPage();
        int pages = Paging.pages(all.size(), per);
        int current = Paging.clamp(page, pages);
        List<ElementButton> buttons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        buttons.add(back());
        actions.add(() -> manage(player, stall));
        buttons.add(ui.button(text("stall.add_stock"), "textures/ui/color_plus"));
        actions.add(() -> addStock(player, stall));
        for (Stock.Line line : all.subList(Paging.from(current, per), Paging.to(current, per, all.size()))) {
            buttons.add(ui.button(text("stall.line_button", line.label(), line.count(), amount(line.price())), null));
            actions.add(() -> line(player, stall, line.key()));
        }
        if (current > 0) { buttons.add(ui.button(text("window.prev"), ShopWindow.BACK)); actions.add(() -> products(player, stall, current - 1)); }
        if (current < pages - 1) { buttons.add(ui.button(text("window.next"), ShopWindow.NEXT)); actions.add(() -> products(player, stall, current + 1)); }
        buttons.add(close());
        actions.add(() -> { });
        ui.menu(player, stall.name, all.isEmpty() ? text("stall.empty_owner") : text("window.list_text", all.size(), current + 1, pages), buttons, actions);
    }

    /** Price and taking back, for one product */
    private void line(Player player, Stall stall, String key) {
        if (!own(player, stall)) return;
        Stock.Line line = stall.stock.find(key);
        if (line == null) { products(player, stall, 0); return; }
        var window = new FormWindowCustom(stall.name);
        window.addElement(new ElementHeader(line.label()));
        window.addElement(new ElementLabel(info(line, stall)));
        window.addElement(new ElementDivider());
        window.addElement(new ElementLabel(text("stall.line_info", line.count(), amount(line.price()))));
        window.addElement(new ElementInput(text("stall.price_box", amount(stalls.maxPrice())), "", String.valueOf(line.price())));
        window.addElement(new ElementInput(text("stall.take_box", line.count()), "0", "0"));
        ui.show(player, window, r -> {
            if (!(r instanceof FormResponseCustom c) || !own(player, stall)) return;
            Stock.Line now = stall.stock.find(key);
            if (now == null) { products(player, stall, 0); return; }
            Long price = StallRules.parseNumber(input(c, 4), 1, stalls.maxPrice()), take = StallRules.parseNumber(input(c, 5), 0, now.count());
            if (price == null) { tell(player, "stall.bad_price", amount(stalls.maxPrice())); line(player, stall, key); return; }
            if (take == null) { tell(player, "stall.bad_amount"); line(player, stall, key); return; }
            if (take > 0) {
                Item back = StallItems.make(now, (int) (long) take);
                if (back == null) { tell(player, "shop.unknown", now.label()); line(player, stall, key); return; }
                if (!player.getInventory().canAddItem(back)) { tell(player, "shop.no_space", now.label(), take); line(player, stall, key); return; }
                stall.stock.price(key, price);
                stall.stock.take(key, (int) (long) take);
                player.getInventory().addItem(back);
                tell(player, "stall.taken", now.label(), take);
            } else {
                stall.stock.price(key, price);
                tell(player, "stall.price_set", now.label(), amount(price));
            }
            stalls.book().save(stall);
            products(player, stall, 0);
        });
    }
}
