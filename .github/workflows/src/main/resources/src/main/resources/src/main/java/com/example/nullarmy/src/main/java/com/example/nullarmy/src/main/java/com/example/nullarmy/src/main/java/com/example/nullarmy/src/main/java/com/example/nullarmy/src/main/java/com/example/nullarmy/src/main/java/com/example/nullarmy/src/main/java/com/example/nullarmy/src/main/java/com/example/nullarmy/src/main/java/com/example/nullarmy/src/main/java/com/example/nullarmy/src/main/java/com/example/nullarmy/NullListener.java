package com.example.nullarmy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

public class NullListener implements Listener {

    private static final long PURGE_COOLDOWN_MS = 30_000L;

    private final NullArmy plugin;
    private final ArmyManager manager;
    private long lastPurge = 0;

    public NullListener(NullArmy plugin, ArmyManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    private Entity source(Entity d) {
        if (d instanceof Projectile pr && pr.getShooter() instanceof Entity s) return s;
        return d;
    }

    // ------------------------------------------------------------------
    // Totem of NULL: sneak + right-click sends all NULL to the void
    // ------------------------------------------------------------------
    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!p.isSneaking()) return;
        ItemStack it = e.getItem();
        if (it == null || !manager.isTotem(it)) return;

        e.setCancelled(true);
        long now = System.currentTimeMillis();
        long left = PURGE_COOLDOWN_MS - (now - lastPurge);
        if (left > 0) {
            p.sendActionBar(Component.text("The Totem is recharging: " + (left / 1000 + 1) + "s",
                    NamedTextColor.GRAY));
            return;
        }
        lastPurge = now;
        int n = manager.purge();
        Bukkit.broadcast(Component.text(
                "The Totem of NULL has been raised. " + n + " NULL were sent to the void.",
                NamedTextColor.DARK_GRAY));
    }

    // ------------------------------------------------------------------
    // Deaths
    // ------------------------------------------------------------------
    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        LivingEntity le = e.getEntity();
        if (!manager.isNullMob(le)) return;
        manager.applyDrops(e.getDrops());
        manager.release(le);
        manager.dropOrder(le);
    }

    // ------------------------------------------------------------------
    // Targeting: only the Coordinator may pick targets
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.HIGH)
    public void onTarget(EntityTargetEvent e) {
        if (!manager.isNullMob(e.getEntity())) return;
        Entity t = e.getTarget();
        if (t == null) return;
        if (manager.isBusy(e.getEntity())) {
            e.setCancelled(true);
            return;
        }
        if (manager.isNullMob(t)) {
            e.setCancelled(true);
            return;
        }
        if (t instanceof Player pl && (manager.isLeader(pl) || !manager.isValidTarget(pl))) {
            e.setCancelled(true);
            return;
        }
        if (e.getReason() != EntityTargetEvent.TargetReason.UNKNOWN) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    // No friendly fire between NULL
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFriendly(EntityDamageByEntityEvent e) {
        if (!manager.isNullMob(e.getEntity())) return;
        if (manager.isNullMob(source(e.getDamager()))) e.setCancelled(true);
    }

    // ------------------------------------------------------------------
    // Hurt by an outsider: free the worker and provoke the squad
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNullHurt(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Mob m) || !manager.isNullMob(m)) return;
        Entity d = source(e.getDamager());
        if (!(d instanceof LivingEntity attacker)) return;
        if (attacker instanceof Mannequin || manager.isNullMob(attacker)) return;
        if (attacker instanceof Player pl && (manager.isLeader(pl) || !manager.isValidTarget(pl))) return;

        if (manager.isBusy(m)) manager.release(m);
        manager.coordinator().provoke(m, attacker);
    }
}
