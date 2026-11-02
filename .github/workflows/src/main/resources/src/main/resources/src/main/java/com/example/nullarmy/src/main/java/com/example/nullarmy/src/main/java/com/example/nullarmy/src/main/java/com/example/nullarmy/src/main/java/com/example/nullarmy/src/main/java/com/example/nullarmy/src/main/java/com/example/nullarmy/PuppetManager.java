package com.example.nullarmy;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Gives every NULL soldier a player-model body (a Mannequin).
 * The real soldier stays as the "brain" but is hidden from players.
 */
public class PuppetManager implements Listener {

    private final NullArmy plugin;
    private final NamespacedKey puppetKey;

    /** soldier UUID -> mannequin */
    private final Map<UUID, Mannequin> puppets = new HashMap<>();
    /** mannequin UUID -> soldier UUID */
    private final Map<UUID, UUID> reverse = new HashMap<>();

    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND
    };

    public PuppetManager(NullArmy plugin) {
        this.plugin = plugin;
        this.puppetKey = new NamespacedKey(plugin, "null_puppet");
    }

    private ArmyManager manager() {
        return plugin.manager();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("npc.enabled", true);
    }

    // ------------------------------------------------------------------
    // Periodic tasks
    // ------------------------------------------------------------------

    /** Every 20 ticks: create bodies for soldiers that don't have one. */
    public void scan() {
        if (!enabled()) return;
        for (World w : Bukkit.getWorlds()) {
            for (Mob m : w.getEntitiesByClass(Mob.class)) {
                if (!manager().isNullMob(m) || !m.isValid()) continue;
                Mannequin existing = puppets.get(m.getUniqueId());
                if (existing == null || !existing.isValid()) {
                    if (existing != null) removeMapping(m.getUniqueId());
                    create(m);
                } else {
                    hideFromAll(m);
                }
            }
        }
    }

    /** Every tick: glue each body to its soldier. */
    public void tick() {
        Iterator<Map.Entry<UUID, Mannequin>> it = puppets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Mannequin> en = it.next();
            Mannequin pup = en.getValue();
            Entity soldier = Bukkit.getEntity(en.getKey());
            if (soldier == null || !soldier.isValid() || !pup.isValid()) {
                reverse.remove(pup.getUniqueId());
                if (pup.isValid()) pup.remove();
                if (soldier != null && soldier.isValid()) show(soldier);
                it.remove();
                continue;
            }
            Location l = soldier.getLocation();
            if (pup.getWorld() != l.getWorld() || pup.getLocation().distanceSquared(l) > 0.0004) {
                pup.teleport(l);
            }
        }
    }

    // ------------------------------------------------------------------
    // Create / remove
    // ------------------------------------------------------------------

    public void create(Mob soldier) {
        if (!enabled()) return;
        Location loc = soldier.getLocation();
        World w = loc.getWorld();
        if (w == null) return;

        Mannequin pup = w.spawn(loc, Mannequin.class, m -> {
            m.setPersistent(false);
            m.setGravity(false);
            m.setImmovable(true);
            m.setSilent(true);
            m.setCollidable(false);
            m.setDescription(null);

            String tex = plugin.getConfig().getString("npc.skin.texture", "");
            String sig = plugin.getConfig().getString("npc.skin.signature", "");
            String user = plugin.getConfig().getString("npc.skin.username", "");
            try {
                if (tex != null && !tex.isBlank() && sig != null && !sig.isBlank()) {
                    ResolvableProfile.Builder b = ResolvableProfile.resolvableProfile();
                    b.uuid(UUID.nameUUIDFromBytes("nullarmy-skin".getBytes()));
                    b.name("NULL");
                    b.addProperty(new ProfileProperty("textures", tex, sig));
                    m.setProfile(b.build());
                } else if (user != null && !user.isBlank()) {
                    m.setProfile(ResolvableProfile.resolvableProfile().name(user).build());
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not apply NPC skin: " + t.getMessage());
            }

            if (soldier.customName() != null) {
                m.customName(soldier.customName());
                m.setCustomNameVisible(true);
            }
            m.getPersistentDataContainer().set(puppetKey, PersistentDataType.BYTE, (byte) 1);

            EntityEquipment from = soldier.getEquipment();
            EntityEquipment to = m.getEquipment();
            if (from != null && to != null) {
                for (EquipmentSlot s : SLOTS) {
                    org.bukkit.inventory.ItemStack it = from.getItem(s);
                    if (it != null && !it.getType().isAir()) to.setItem(s, it.clone());
                }
            }
        });

        puppets.put(soldier.getUniqueId(), pup);
        reverse.put(pup.getUniqueId(), soldier.getUniqueId());

        soldier.setSilent(true);
        hideFromAll(soldier);
    }

    /** Remove a soldier's body so scan() makes a fresh one (new gear/name). */
    public void rebuild(Entity soldier) {
        removeMapping(soldier.getUniqueId());
    }

    private void removeMapping(UUID soldierId) {
        Mannequin pup = puppets.remove(soldierId);
        if (pup != null) {
            reverse.remove(pup.getUniqueId());
            if (pup.isValid()) pup.remove();
        }
    }

    public void removeAll() {
        for (Map.Entry<UUID, Mannequin> en : new HashMap<>(puppets).entrySet()) {
            Entity soldier = Bukkit.getEntity(en.getKey());
            if (soldier != null && soldier.isValid()) show(soldier);
            if (en.getValue().isValid()) en.getValue().remove();
        }
        puppets.clear();
        reverse.clear();
    }

    // ------------------------------------------------------------------
    // Visibility helpers
    // ------------------------------------------------------------------

    private void hideFromAll(Entity soldier) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.hideEntity(plugin, soldier);
        }
    }

    public void show(Entity soldier) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showEntity(plugin, soldier);
        }
    }

    private Mannequin puppetOf(Entity soldier) {
        if (soldier == null) return null;
        Mannequin m = puppets.get(soldier.getUniqueId());
        return (m != null && m.isValid()) ? m : null;
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    /** Hits on the body are forwarded to the real soldier. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPuppetDamage(EntityDamageEvent e) {
        UUID sid = reverse.get(e.getEntity().getUniqueId());
        if (sid == null) return;
        e.setCancelled(true);
        if (!(e instanceof EntityDamageByEntityEvent be)) return;

        Entity soldier = Bukkit.getEntity(sid);
        if (!(soldier instanceof LivingEntity ls) || !ls.isValid()) return;

        Entity attacker = be.getDamager();
        if (attacker instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) {
            attacker = shooter;
        }
        if (attacker instanceof Player pl && manager().isLeader(pl)) return;
        if (manager().isNullMob(attacker)) return;

        ls.damage(be.getDamage(), attacker);
    }

    /** Soldier got hurt -> body plays the hurt animation. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSoldierHurt(EntityDamageEvent e) {
        Mannequin pup = puppetOf(e.getEntity());
        if (pup == null) return;
        pup.playHurtAnimation(0f);
        pup.getWorld().playSound(pup.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 1f);
    }

    /** Soldier attacks -> body swings. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSoldierAttack(EntityDamageByEntityEvent e) {
        Mannequin pup = puppetOf(e.getDamager());
        if (pup != null) pup.swingMainHand();
    }

    /** Archer shoots -> body swings. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSoldierShoot(EntityShootBowEvent e) {
        Mannequin pup = puppetOf(e.getEntity());
        if (pup != null) pup.swingMainHand();
    }

    @EventHandler
    public void onSoldierDeath(EntityDeathEvent e) {
        Mannequin pup = puppetOf(e.getEntity());
        if (pup == null) return;
        Location l = pup.getLocation().add(0, 1, 0);
        l.getWorld().spawnParticle(Particle.LARGE_SMOKE, l, 30, 0.3, 0.6, 0.3, 0.02);
        l.getWorld().playSound(l, Sound.ENTITY_PLAYER_DEATH, 1f, 0.8f);
        removeMapping(e.getEntity().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (UUID id : puppets.keySet()) {
                Entity s = Bukkit.getEntity(id);
                if (s != null && s.isValid()) p.hideEntity(plugin, s);
            }
        }, 5L);
    }
}
