package dev.toka.shop;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.SignChangeEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The stall signs. The owner writes the sign tag (config stall.sign-tag, "[stall]") on line 1 and the stall name on line 2; the plugin checks
 * the claim and rewrites the sign. A click opens the stall for a visitor and the owner window for the owner. The position lookup is a map.
 */
final class StallSigns implements Listener {
    private final Main plugin;
    private final Stalls stalls;
    private final StallWindow window;
    private final Map<UUID, Long> clicked = new HashMap<>();

    StallSigns(Main plugin, Stalls stalls, StallWindow window) {
        this.plugin = plugin;
        this.stalls = stalls;
        this.window = window;
    }

    private String text(String key, Object... args) { return plugin.messages().text(key, args); }

    @EventHandler public void onSign(SignChangeEvent event) {
        if (!StallRules.cleanName(event.getLine(0)).equalsIgnoreCase(stalls.signTag())) return;
        Player player = event.getPlayer();
        if (!player.hasPermission("tokashop.stall")) { event.setCancelled(); player.sendMessage(text("stall.no_permission")); return; }
        var own = stalls.book().of(player.getUniqueId());
        String name = StallRules.cleanName(event.getLine(1));
        Stall stall = name.isEmpty() ? (own.size() == 1 ? own.get(0) : null) : stalls.book().byName(name);
        if (stall == null) { event.setCancelled(); player.sendMessage(text("stall.sign_no_stall")); return; }
        if (!stall.owner.equals(player.getUniqueId())) { event.setCancelled(); player.sendMessage(text("stall.not_yours")); return; }
        Block block = event.getBlock();
        String world = block.getLevel().getFolderName();
        java.util.List<String> claims = java.util.List.of();
        if (stalls.land().installed()) {
            LandLink.Standing standing = stalls.land().manage(player, world, block.getFloorX(), block.getFloorY(), block.getFloorZ());
            if (!standing.known()) { event.setCancelled(); player.sendMessage(text("stall.land_unknown")); return; }
            if (!standing.allowed()) { event.setCancelled(); player.sendMessage(text("stall.sign_outside")); return; }
            claims = standing.claims();
        } else if (!player.hasPermission("tokashop.stall.anywhere")) {
            event.setCancelled();
            player.sendMessage(text("stall.sign_no_anywhere"));
            return;
        }
        stalls.book().link(stall, world, block.getFloorX(), block.getFloorY(), block.getFloorZ(), claims);
        String[] lines = stalls.lines(stall);
        for (int i = 0; i < 4; i++) event.setLine(i, lines[i]);
        player.sendMessage(text("stall.sign_placed", stall.name));
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void onClick(PlayerInteractEvent event) {
        if (event.getAction() != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getBlock();
        Stall stall = stalls.book().bySign(block.getLevel().getFolderName(), block.getFloorX(), block.getFloorY(), block.getFloorZ());
        if (stall == null) return;
        event.setCancelled();
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Long last = clicked.put(player.getUniqueId(), now);
        if (last != null && now - last < 600) return;
        if (stall.owner.equals(player.getUniqueId())) {
            stalls.recheck(stall);
            window.manage(player, stall);
        } else window.visit(player, stall);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Stall stall = stalls.book().bySign(block.getLevel().getFolderName(), block.getFloorX(), block.getFloorY(), block.getFloorZ());
        if (stall == null) return;
        Player player = event.getPlayer();
        if (!stall.owner.equals(player.getUniqueId()) && !player.hasPermission("tokashop.admin")) {
            event.setCancelled();
            player.sendMessage(text("stall.sign_protected"));
            return;
        }
        stalls.book().unlink(stall);
        player.sendMessage(text("stall.sign_removed", stall.name));
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> { if (player.isOnline()) stalls.arrived(player); }, 100);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { clicked.remove(event.getPlayer().getUniqueId()); }
}
