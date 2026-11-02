package com.example.nullarmy;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BannerPatternLayers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.DyeColor;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Wiki lore: shields, commanders, dramatic arrivals, totem-pop stasis. */
public class Lore implements Listener {

    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private final NullArmy plugin;
    private final ArmyManager manager;
    private final NamespacedKey commanderKey;

    private final Map<UUID, Long> lastArrival = new HashMap<>();
    private final Map<UUID, Long> popCooldown = new HashMap<>();

    public Lore(NullArmy plugin, ArmyManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.commanderKey = new NamespacedKey(plugin, "null_commander");
    }

    public boolean isCommander(Entity e) {
        return e.getPersistentDataContainer().has(commanderKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------
    // Called for every freshly spawned soldier
    // ------------------------------------------------------------------

    public void finish(Mob m, NullRank r) {
        EntityEquipment eq = m.getEquipment();
        if (eq == null) return;

        if (plugin.getConfig().getBoolean("lore.shield", true)) {
            eq.setItemInOffHand(buildShield());
            eq.setItemInOffHandDropChance(0f);
        }

        if (r == NullRank.GENERAL) {
            double chance = plugin.getConfig().getDouble("lore.commander-chance", 0.2);
            if (ThreadLocalRandom.current().nextDouble() < chance) {
                promote(m);
            } else {
                m.customName(Component.text(roleName(0.5), NamedTextColor.GRAY));
                m.setCustomNameVisible(true);
            }
        }

        arrival(m);
    }

    private ItemStack buildShield() {
        ItemStack shield = new ItemStack(Material.SHIELD);
        ItemMeta meta = shield.getItemMeta();
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        meta.addEnchant(Enchantment.MENDING, 1, true);
        shield.setItemMeta(meta);
        try {
            shield.setData(DataComponentTypes.BASE_COLOR, DyeColor.BLACK);
            shield.setData(DataComponentTypes.BANNER_PATTERNS,
                    BannerPatternLayers.bannerPatternLayers()
                            .add(new Pattern(DyeColor.GRAY, PatternType.SKULL))
                            .build());
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not style NULL shield: " + t.getMessage());
        }
        return shield;
    }

    private String roleName(double underscoreChance) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int len = plugin.getConfig().getInt("names.length", 24);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (rnd.nextDouble() < underscoreChance) sb.append('_');
            else sb.append(ALNUM.charAt(rnd.nextInt(ALNUM.length())));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Commanders
    // ------------------------------------------------------------------

    /** Promote up to n soldiers near the location. Returns how many were promoted. */
    public int promoteNearest(Location loc, int n) {
        World w = loc.getWorld();
        if (w == null) return 0;
        List<Mob> pool = new ArrayList<>();
        for (Mob m : w.getEntitiesByClass(Mob.class)) {
            if (!manager.isNullMob(m) || !m.isValid() || isCommander(m)) continue;
            pool.add(m);
        }
        pool.sort(Comparator
                .comparingInt((Mob m) -> manager.rankOf(m) == NullRank.GENERAL ? 0 : 1)
                .thenComparingDouble(m -> m.getLocation().distanceSquared(loc)));
        int done = 0;
        for (Mob m : pool) {
            if (done >= n) break;
            promote(m);
            done++;
        }
        return done;
    }

    public void promote(Mob m) {
        EntityEquipment eq = m.getEquipment();
        if (eq == null) return;

        // Quartz-trimmed chestplate
        ItemStack chest = eq.getChestplate();
        if (chest == null || chest.getType().isAir()) {
            chest = new ItemStack(Material.NETHERITE_CHESTPLATE);
        }
        if (chest.getItemMeta() instanceof ArmorMeta am) {
            TrimPattern pattern = null;
            if (am.hasTrim()) pattern = am.getTrim().getPattern();
            if (pattern == null) pattern = Registry.TRIM_PATTERN.get(NamespacedKey.minecraft("flow"));
            if (pattern == null) pattern = Registry.TRIM_PATTERN.get(NamespacedKey.minecraft("silence"));
            if (pattern != null) am.setTrim(new ArmorTrim(TrimMaterial.QUARTZ, pattern));
            chest.setItemMeta(am);
        }
        eq.setChestplate(chest);
        eq.setChestplateDropChance(0f);

        // Mace, or sometimes a netherite spear
        Material weaponMat = Material.MACE;
        Material spear = Material.getMaterial("NETHERITE_SPEAR");
        if (spear != null && ThreadLocalRandom.current().nextDouble() < 0.4) weaponMat = spear;
        ItemStack weapon = new ItemStack(weaponMat);
        ItemMeta wm = weapon.getItemMeta();
        wm.addEnchant(Enchantment.UNBREAKING, 3, true);
        wm.addEnchant(Enchantment.MENDING, 1, true);
        wm.addEnchant(weaponMat == Material.MACE ? Enchantment.DENSITY : Enchantment.SHARPNESS,
                5, true);
        weapon.setItemMeta(wm);
        eq.setItemInMainHand(weapon);
        eq.setItemInMainHandDropChance(0f);

        // Stats
        AttributeInstance hp = m.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) {
            hp.setBaseValue(200);
            m.setHealth(200);
        }
        AttributeInstance dmg = m.getAttribute(Attribute.ATTACK_DAMAGE);
        if (dmg != null) dmg.setBaseValue(14);

        m.getPersistentDataContainer().set(commanderKey, PersistentDataType.BYTE, (byte) 1);

        // Name: all underscores with one letter, like ____A____
        int len = plugin.getConfig().getInt("names.length", 24);
        char[] name = new char[len];
        java.util.Arrays.fill(name, '_');
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        name[rnd.nextInt(len)] = LETTERS.charAt(rnd.nextInt(LETTERS.length()));
        m.customName(Component.text(new String(name), NamedTextColor.DARK_GRAY));
        m.setCustomNameVisible(true);

        manager.puppets().rebuild(m);
    }

    // ------------------------------------------------------------------
    // Arrival: lightning + darkening sky
    // ------------------------------------------------------------------

    public void arrival(Mob m) {
        World w = m.getWorld();
        long now = System.currentTimeMillis();
        Long last = lastArrival.get(w.getUID());
        if (last != null && now - last < 4000) return;
        lastArrival.put(w.getUID(), now);

        if (plugin.getConfig().getBoolean("lore.lightning", true)) {
            w.strikeLightningEffect(m.getLocation());
        }
        if (plugin.getConfig().getBoolean("lore.dark-sky", true)
                && w.getEnvironment() == World.Environment.NORMAL) {
            w.setStorm(true);
            w.setThundering(true);
            w.setWeatherDuration(90 * 20);
            w.setThunderDuration(90 * 20);
        }
    }

    // ------------------------------------------------------------------
    // Totem-pop stasis: fatal hit -> survive and teleport away
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFatal(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Mob m) || !manager.isNullMob(m)) return;
        if (!plugin.getConfig().getBoolean("lore.totem-pop.enabled", true)) return;

        EntityDamageEvent.DamageCause c = e.getCause();
        if (c == EntityDamageEvent.DamageCause.VOID
                || c == EntityDamageEvent.DamageCause.KILL
                || c == EntityDamageEvent.DamageCause.SUICIDE) return;
        if (m.getHealth() - e.getFinalDamage() > 0) return;

        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getLong("lore.totem-pop.cooldown-seconds", 60) * 1000L;
        Long last = popCooldown.get(m.getUniqueId());
        if (last != null && now - last < cd) return;
        popCooldown.put(m.getUniqueId(), now);

        e.setCancelled(true);

        AttributeInstance maxAttr = m.getAttribute(Attribute.MAX_HEALTH);
        double max = maxAttr != null ? maxAttr.getValue() : 40;
        m.setHealth(Math.min(max, Math.max(2, max * 0.5)));
        m.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));
        m.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));

        Location from = m.getLocation().add(0, 1, 0);
        World w = from.getWorld();
        w.spawnParticle(Particle.TOTEM_OF_UNDYING, from, 60, 0.4, 0.8, 0.4, 0.4);
        w.playSound(from, Sound.ITEM_TOTEM_USE, 1f, 1f);

        if (w.getEnvironment() == World.Environment.NETHER) return;

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double angle = rnd.nextDouble() * Math.PI * 2;
        double dist = 20 + rnd.nextDouble() * 20;
        int x = (int) Math.floor(from.getX() + Math.cos(angle) * dist);
        int z = (int) Math.floor(from.getZ() + Math.sin(angle) * dist);
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return;

        int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        if (y <= w.getMinHeight() + 1) return;
        if (w.getBlockAt(x, y - 1, z).isLiquid()) return;

        Location to = new Location(w, x + 0.5, y, z + 0.5, from.getYaw(), from.getPitch());
        m.teleport(to);
        w.spawnParticle(Particle.TOTEM_OF_UNDYING, to.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.3);
        w.playSound(to, Sound.ITEM_TOTEM_USE, 1f, 1f);
    }
}
