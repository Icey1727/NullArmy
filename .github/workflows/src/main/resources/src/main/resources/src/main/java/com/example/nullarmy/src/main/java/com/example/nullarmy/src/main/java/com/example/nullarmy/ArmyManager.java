package com.example.nullarmy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.entity.WitherSkull;
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
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class ArmyManager {

    private static final String CH = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final NullArmy plugin;
    public final NamespacedKey mobKey;
    public final NamespacedKey totemKey;

    private final Set<UUID> busy = new HashSet<>();
    private final Map<UUID, UUID> orders = new HashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();

    private final Coordinator coordinator;
    private final TotemFollower follower;
    private final PuppetManager puppets;
    private final Lore lore;
    private final WorkerManager workers;

    public ArmyManager(NullArmy plugin) {
        this.plugin = plugin;
        this.mobKey = new NamespacedKey(plugin, "null_mob");
        this.totemKey = new NamespacedKey(plugin, "null_totem");
        this.coordinator = new Coordinator(plugin, this);
        this.follower = new TotemFollower(plugin, this);
        this.puppets = new PuppetManager(plugin);
        Bukkit.getPluginManager().registerEvents(puppets, plugin);
        this.lore = new Lore(plugin, this);
        Bukkit.getPluginManager().registerEvents(lore, plugin);
        this.workers = new WorkerManager(plugin, this);
    }

    public Coordinator coordinator() { return coordinator; }
    public TotemFollower follower() { return follower; }
    public PuppetManager puppets() { return puppets; }
    public Lore lore() { return lore; }
    public WorkerManager workers() { return workers; }

    // ---------- identity ----------

    public boolean isNullMob(Entity e) {
        return e != null && e.getPersistentDataContainer().has(mobKey, PersistentDataType.STRING);
    }

    public NullRank rankOf(Entity e) {
        if (e == null) return null;
        String s = e.getPersistentDataContainer().get(mobKey, PersistentDataType.STRING);
        return s == null ? null : NullRank.parse(s);
    }

    public boolean isTotem(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(totemKey, PersistentDataType.BYTE);
    }

    public boolean isLeader(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isTotem(it)) return true;
        }
        return false;
    }

    public boolean isValidTarget(Player p) {
        if (p.isDead() || !p.isOnline()) return false;
        GameMode g = p.getGameMode();
        if (g != GameMode.SURVIVAL && g != GameMode.ADVENTURE) return false;
        return !isLeader(p);
    }

    public Component scrambledName() {
        FileConfiguration c = plugin.getConfig();
        int len = Math.max(4, c.getInt("names.length", 24));
        ThreadLocalRandom r = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(CH.charAt(r.nextInt(CH.length())));
        return Component.text(sb.toString(), NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.OBFUSCATED, c.getBoolean("names.obfuscated", false))
                .decoration(TextDecoration.ITALIC, false);
    }

    // ---------- busy / orders ----------

    public boolean isBusy(Entity e) { return busy.contains(e.getUniqueId()); }

    /** Workers call this to claim/free a soldier. */
    public void setBusy(Mob m, boolean b) {
        if (b) busy.add(m.getUniqueId()); else busy.remove(m.getUniqueId());
    }

    /** Frees a soldier from any job and orders. */
    public void release(Entity e) {
        busy.remove(e.getUniqueId());
        orders.remove(e.getUniqueId());
        workers.release(e);
        if (e instanceof Mob m) m.getPathfinder().stopPathfinding();
    }

    public UUID orderOf(Entity e) { return orders.get(e.getUniqueId()); }
    public void dropOrder(Entity e) { orders.remove(e.getUniqueId()); }

    public void clearOrders() {
        orders.clear();
        coordinator.reset();
        for (Mob m : allMobs()) m.setTarget(null);
    }

    public List<Mob> allMobs() {
        List<Mob> out = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            for (Mob m : w.getEntitiesByClass(Mob.class)) {
                if (isNullMob(m)) out.add(m);
            }
        }
        return out;
    }

    // ---------- spawning ----------

    public Mob spawn(Location loc, NullRank rank) {
        final NullRank r = rank == null ? NullRank.random() : rank;
        World w = loc.getWorld();
        WitherSkeleton m = w.spawn(loc, WitherSkeleton.class, mob -> {
            mob.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, r.name());
            mob.setPersistent(true);
            mob.setRemoveWhenFarAway(false);
            mob.setCanPickupItems(false);
            mob.customName(scrambledName());
            mob.setCustomNameVisible(true);
            setStat(mob, Attribute.MAX_HEALTH, r.health);
            mob.setHealth(r.health);
            setStat(mob, Attribute.MOVEMENT_SPEED, r.speed);
            setStat(mob, Attribute.FOLLOW_RANGE, r.follow);
            if (r.damage > 0) setStat(mob, Attribute.ATTACK_DAMAGE, r.damage);
            equip(mob, r);
            applyBuffs(mob);
        });
        w.spawnParticle(Particle.REVERSE_PORTAL, loc.clone().add(0, 1, 0), 40, 0.3, 0.8, 0.3, 0.05);
        lore.finish(m, r);
        return m;
    }

    /** rank == null means a random rank for each soldier. */
    public List<Mob> spawnAround(Location center, int amount, NullRank rank, double minDist, double maxDist) {
        List<Mob> out = new ArrayList<>();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < amount; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            double d = minDist + r.nextDouble() * Math.max(0, maxDist - minDist);
            Location base = center.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d);
            out.add(spawn(findGround(base), rank));
        }
        return out;
    }

    private Location findGround(Location base) {
        World w = base.getWorld();
        int x = base.getBlockX(), z = base.getBlockZ(), by = base.getBlockY();
        for (int off = 0; off <= 12; off++) {
            for (int s : new int[]{off, -off}) {
                int y = by + s;
                if (y <= w.getMinHeight() + 1 || y >= w.getMaxHeight() - 3) continue;
                Block feet = w.getBlockAt(x, y, z);
                Block head = w.getBlockAt(x, y + 1, z);
                Block below = w.getBlockAt(x, y - 1, z);
                if (feet.isPassable() && !feet.isLiquid() && head.isPassable() && !below.isPassable()) {
                    return new Location(w, x + 0.5, y, z + 0.5, base.getYaw(), 0);
                }
            }
        }
        return base.clone();
    }

    private void setStat(LivingEntity e, Attribute attr, double v) {
        AttributeInstance a = e.getAttribute(attr);
        if (a != null) a.setBaseValue(v);
    }

    public void applyBuffs(LivingEntity e) {
        e.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 1, false, false));
        e.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 1, false, false));
        e.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
    }

    // ---------- gear ----------

    private void equip(Mob m, NullRank rank) {
        EntityEquipment eq = m.getEquipment();
        if (eq == null) return;
        boolean general = rank == NullRank.GENERAL;
        eq.setHelmet(buildNetheriteArmor(Material.NETHERITE_HELMET, false));
        eq.setChestplate(buildNetheriteArmor(Material.NETHERITE_CHESTPLATE, general));
        eq.setLeggings(buildNetheriteArmor(Material.NETHERITE_LEGGINGS, false));
        eq.setBoots(buildNetheriteArmor(Material.NETHERITE_BOOTS, false));
        eq.setItemInMainHand(buildWeapon(rank));
        eq.setHelmetDropChance(0f);
        eq.setChestplateDropChance(0f);
        eq.setLeggingsDropChance(0f);
        eq.setBootsDropChance(0f);
        eq.setItemInMainHandDropChance(0f);
        eq.setItemInOffHandDropChance(0f);
    }

    public ItemStack buildNetheriteArmor(Material mat, boolean redstoneTrim) {
        ItemStack it = new ItemStack(mat);
        ench(it, "protection", 4);
        ench(it, "unbreaking", 3);
        ench(it, "mending", 1);
        if (it.getItemMeta() instanceof ArmorMeta meta) {
            TrimPattern pattern = Registry.TRIM_PATTERN.get(NamespacedKey.minecraft("flow"));
            if (pattern == null) pattern = TrimPattern.SILENCE;
            TrimMaterial tm = redstoneTrim ? TrimMaterial.REDSTONE : TrimMaterial.NETHERITE;
            meta.setTrim(new ArmorTrim(tm, pattern));
            it.setItemMeta(meta);
        }
        return it;
    }

    public ItemStack buildWeapon(NullRank rank) {
        ItemStack it;
        if (rank == NullRank.ARCHER) {
            it = new ItemStack(Material.BOW);
            ench(it, "power", 5);
            ench(it, "flame", 1);
            ench(it, "infinity", 1);
        } else {
            it = new ItemStack(Material.NETHERITE_SWORD);
            ench(it, "sharpness", 5);
            if (rank == NullRank.HUNTER || rank == NullRank.GENERAL) ench(it, "fire_aspect", 2);
        }
        ench(it, "unbreaking", 3);
        ench(it, "mending", 1);
        return it;
    }

    public ItemStack buildPickaxe() {
        ItemStack it = new ItemStack(Material.NETHERITE_PICKAXE);
        ench(it, "efficiency", 5);
        ench(it, "unbreaking", 3);
        ench(it, "mending", 1);
        return it;
    }

    public ItemStack buildAxe() {
        ItemStack it = new ItemStack(Material.NETHERITE_AXE);
        ench(it, "sharpness", 5);
        ench(it, "efficiency", 5);
        ench(it, "unbreaking", 3);
        ench(it, "mending", 1);
        return it;
    }

    private void ench(ItemStack it, String key, int lvl) {
        Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
        if (e != null) it.addUnsafeEnchantment(e, lvl);
    }

    // ---------- totem ----------

    public ItemStack buildTotem() {
        ItemStack it = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("Totem of NULL", NamedTextColor.DARK_PURPLE)
                .decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Whoever holds this leads NULL.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Sneak + right-click to send all of NULL to the void.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(totemKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    public void giveTotem(Player p) {
        for (ItemStack over : p.getInventory().addItem(buildTotem()).values())
            p.getWorld().dropItemNaturally(p.getLocation(), over);
    }

    // ---------- recruiting / orders ----------

    private List<Mob> nearbyFree(Location center, double radius, boolean skipOrdered) {
        List<Mob> list = new ArrayList<>();
        for (Entity e : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(e instanceof Mob m) || !isNullMob(m)) continue;
            if (isBusy(m)) continue;
            if (skipOrdered && orderOf(m) != null) continue;
            list.add(m);
        }
        list.sort(Comparator.comparingDouble(m -> m.getLocation().distanceSquared(center)));
        return list;
    }

    /** Free soldiers near a spot for a job. Spawns Soldiers if there aren't enough. */
    public List<Mob> recruit(Location center, int count) {
        double radius = plugin.getConfig().getDouble("workers.recruit-radius", 64);
        List<Mob> pool = nearbyFree(center, radius, true);
        List<Mob> out = new ArrayList<>(pool.subList(0, Math.min(count, pool.size())));
        int missing = count - out.size();
        if (missing > 0) out.addAll(spawnAround(center, missing, NullRank.SOLDIER, 2, 5));
        return out;
    }

    /** Orders soldiers to hunt a target. Returns how many were ordered. */
    public int orderAttack(Location origin, LivingEntity target, int count) {
        double radius = plugin.getConfig().getDouble("workers.recruit-radius", 64);
        List<Mob> pool = nearbyFree(origin, radius, false);
        List<Mob> out = new ArrayList<>(pool.subList(0, Math.min(count, pool.size())));
        int missing = count - out.size();
        if (missing > 0) out.addAll(spawnAround(target.getLocation(), missing, null, 24, 34));
        for (Mob m : out) orders.put(m.getUniqueId(), target.getUniqueId());
        return out.size();
    }

    // ---------- drops ----------

    /** Call from the death event: replaces the normal drops. */
    public void applyDrops(List<ItemStack> drops) {
        FileConfiguration c = plugin.getConfig();
        drops.clear();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int pearls = r.nextInt(c.getInt("drops.ender-pearl-max", 2) + 1);
        if (pearls > 0) drops.add(new ItemStack(Material.ENDER_PEARL, pearls));
        if (r.nextDouble() < c.getDouble("drops.shulker-shell-chance", 0.10))
            drops.add(new ItemStack(Material.SHULKER_SHELL, 1));
    }

    // ---------- purge ----------

    /** Sends every NULL soldier to the void. Returns how many. */
    public int purge() {
        int n = 0;
        for (Mob m : allMobs()) {
            Location l = m.getLocation().add(0, 1, 0);
            l.getWorld().spawnParticle(Particle.REVERSE_PORTAL, l, 30, 0.3, 0.8, 0.3, 0.1);
            l.getWorld().playSound(l, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.6f);
            release(m);
            m.remove();
            n++;
        }
        orders.clear();
        busy.clear();
        coordinator.reset();
        return n;
    }

    public int clear() { return purge(); }

    // ---------- weapons ----------

    public void orbitalStrike(Location target) {
        FileConfiguration c = plugin.getConfig();
        final int skulls = c.getInt("strike.skulls", 40);
        final int duration = Math.max(1, c.getInt("strike.duration-ticks", 100));
        final double radius = c.getDouble("strike.radius", 8);
        final double height = c.getDouble("strike.height", 60);
        final float yield = (float) c.getDouble("strike.yield", 1.5);
        final World w = target.getWorld();
        final double top = Math.min(w.getMaxHeight() - 2, target.getY() + height);
        w.playSound(target, Sound.ENTITY_WITHER_SPAWN, 4f, 0.6f);

        new BukkitRunnable() {
            int tick = 0;
            int fired = 0;

            @Override
            public void run() {
                if (tick >= duration) { cancel(); return; }
                for (double y = target.getY(); y < top; y += 3) {
                    w.spawnParticle(Particle.END_ROD, target.getX(), y, target.getZ(), 1, 0.2, 0, 0.2, 0);
                }
                int shouldHave = (int) ((tick + 1) * (long) skulls / duration);
                ThreadLocalRandom r = ThreadLocalRandom.current();
                while (fired < shouldHave) {
                    fired++;
                    double a = r.nextDouble() * Math.PI * 2;
                    double d = Math.sqrt(r.nextDouble()) * radius;
                    Location s = new Location(w, target.getX() + Math.cos(a) * d, top, target.getZ() + Math.sin(a) * d);
                    boolean charged = r.nextInt(4) == 0;
                    w.spawn(s, WitherSkull.class, sk -> {
                        sk.setDirection(new Vector(0, -1, 0));
                        sk.setVelocity(new Vector(0, -1.5, 0));
                        sk.setYield(yield);
                        sk.setCharged(charged);
                    });
                }
                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    public void portalStrike(Location center, int amount) {
        final int delay = plugin.getConfig().getInt("portal.delay-ticks", 60);
        final World w = center.getWorld();
        w.playSound(center, Sound.BLOCK_PORTAL_TRIGGER, 2f, 0.8f);

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= delay) {
                    cancel();
                    w.strikeLightningEffect(center);
                    w.playSound(center, Sound.ENTITY_ENDERMAN_TELEPORT, 2f, 0.5f);
                    spawnAround(center, amount, null, 2, 6);
                    return;
                }
                for (int i = 0; i < 12; i++) {
                    double a = Math.PI * 2 * i / 12.0 + t * 0.3;
                    w.spawnParticle(Particle.PORTAL, center.getX() + Math.cos(a) * 2.5,
                            center.getY() + 1, center.getZ() + Math.sin(a) * 2.5, 4, 0.1, 1.0, 0.1, 0.5);
                }
                t += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    // ---------- tasks ----------

    public void startTasks() {
        stopTasks();
        int interval = Math.max(2, plugin.getConfig().getInt("coordination.interval-ticks", 10));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, coordinator::tick, 40L, interval));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, follower::tick, 41L, interval));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, puppets::tick, 20L, 1L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, puppets::scan, 20L, 20L));
        long patrol = Math.max(5, plugin.getConfig().getInt("patrols.interval-seconds", 45)) * 20L;
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::patrolTick, patrol, patrol));
    }

    public void stopTasks() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
    }

    /** End patrols: groups of NULL appear near players in the End. */
    private void patrolTick() {
        FileConfiguration c = plugin.getConfig();
        if (!c.getBoolean("patrols.enabled", true)) return;
        int group = c.getInt("patrols.group-size", 3);
        int max = c.getInt("patrols.max-nearby", 12);
        double radius = c.getDouble("patrols.check-radius", 40);
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() != World.Environment.THE_END) continue;
            for (Player p : w.getPlayers()) {
                if (!isValidTarget(p)) continue;
                int near = 0;
                for (Entity e : p.getNearbyEntities(radius, radius, radius)) {
                    if (isNullMob(e)) near++;
                }
                if (near >= max) continue;
                spawnAround(p.getLocation(), group, null, 18, 28);
            }
        }
    }
}
