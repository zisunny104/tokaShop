package dev.toka.shop;

import cn.nukkit.Player;
import cn.nukkit.item.Item;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Items to stock lines and back. A line keeps the id, the damage and the whole NBT of the item, so nothing about it is lost or merged away. */
final class StallItems {
    private StallItems() {}

    /** An inventory stack and the slot it sits in */
    record Held(int slot, Item item) {}

    /** The line that describes this item (count 0, price 0); the caller fills those in */
    static Stock.Line describe(Item item) {
        byte[] nbt = item.hasCompoundTag() ? item.getCompoundTag() : null;
        String tag = nbt == null || nbt.length == 0 ? "" : Base64.getEncoder().encodeToString(nbt);
        String custom = item.getCustomName();
        String label = StallRules.cleanName(custom == null || custom.isBlank() ? item.getName() : custom);
        if (label.isEmpty()) label = item.getName();
        return new Stock.Line(Stock.Line.keyOf(item.getId(), item.getDamage(), tag), item.getId(), item.getDamage(), tag, label, 0, 0);
    }

    /** The item of a line, or null when this server cannot make it */
    static Item make(Stock.Line line, int count) {
        try {
            byte[] nbt = line.tag().isEmpty() ? null : Base64.getDecoder().decode(line.tag());
            Item item = nbt == null ? Item.get(line.id(), line.damage(), count) : Item.get(line.id(), line.damage(), count, nbt);
            return item == null || item.isNull() || item.getId() == 0 ? null : item;
        } catch (RuntimeException | LinkageError unknown) { return null; }
    }

    /** The largest stack of the line's item; 1 when unknown */
    static int stackSize(Stock.Line line) {
        Item item = make(line, 1);
        return item == null ? 1 : Math.max(1, item.getMaxStackSize());
    }

    /** The vanilla name, which may differ from the label when the item was renamed; empty when unknown */
    static String baseName(Stock.Line line) {
        Item item = make(line, 1);
        return item == null ? "" : item.getName();
    }

    /** What the player carries, by slot, without empty slots */
    static List<Held> carried(Player player) {
        List<Held> out = new ArrayList<>();
        for (Map.Entry<Integer, Item> e : new TreeMap<>(player.getInventory().getContents()).entrySet()) {
            Item item = e.getValue();
            if (item != null && !item.isNull() && item.getId() != 0 && item.getCount() > 0) out.add(new Held(e.getKey(), item));
        }
        return out;
    }
}
