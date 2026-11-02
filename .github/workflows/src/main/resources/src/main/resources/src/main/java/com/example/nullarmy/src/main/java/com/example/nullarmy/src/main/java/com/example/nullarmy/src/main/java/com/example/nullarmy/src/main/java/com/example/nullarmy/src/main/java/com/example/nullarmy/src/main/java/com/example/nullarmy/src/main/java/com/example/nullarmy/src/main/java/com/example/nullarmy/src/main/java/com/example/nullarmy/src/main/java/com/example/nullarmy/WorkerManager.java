package com.example.nullarmy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Miners and builders. Workers are ordinary NULL soldiers. */
public class WorkerManager {

    private static final int STEP = 4; // ticks between job updates
    private static final BlockFace[] FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
            BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final NullArmy plugin;
    private final ArmyManager manager;
    private final List<Job> jobs = new ArrayList<>();
    /** worker UUID -> the item they were holding before the job */
    private final Map<UUID, ItemStack> saved = new HashMap<>();
    private BukkitTask task;

    public WorkerManager(NullArmy plugin, ArmyManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    private int cfg(String key, int def) {
        return plugin.getConfig().getInt("workers." + key, def);
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Returns how many workers started (0 = nothing happened). */
    public int startMining(Player owner, OreType type, int workers, int amount, int radius) {
        radius = Math.max(4, Math.min(radius, cfg("max-radius", 32)));
        workers = Math.max(1, Math.min(workers, 20));
        amount = Math.max(1, amount);

        List<Mob> crew = recruitCrew(owner.getLocation(), workers);
        if (crew.isEmpty()) return 0;

        MinerJob job = new MinerJob(owner.getUniqueId(), owner.getLocation(), type, amount, radius);
        ItemStack pick = manager.buildPickaxe();
        for (Mob m : crew) {
            takeOver(m, pick.clone());
            job.workers.add(m.getUniqueId());
        }
        register(job);
        return crew.size();
    }

    /** size <= 0 uses the default size. Returns how many workers started. */
    public int startBuilding(Player owner, Structures.Type type, int workers, int size) {
        workers = Math.max(1, Math.min(workers, 20));
        int sz = type.clamp(size);
        List<Structures.Piece> pieces = new ArrayList<>(Structures.generate(type, sz));
        if (pieces.isEmpty()) return 0;

        World w = owner.getWorld();
        BlockFace face = owner.getFacing();
        int fx = face.getModX();
        int fz = face.getModZ();
        if (fx == 0 && fz == 0) fz = 1;
        int rx = -fz;
        int rz = fx;

        int dist = (type == Structures.Type.WALL || type == Structures.Type.PLATFORM) ? 3 : 4;
        Location ol = owner.getLocation();
        int ox = ol.getBlockX() + fx * dist;
        int oz = ol.getBlockZ() + fz * dist;
        int baseY = ol.getBlockY();
        int lowest = Math.max(w.getMinHeight() + 1, baseY - 10);
        while (baseY > lowest && w.getBlockAt(ox, baseY - 1, oz).isPassable()
                && !w.getBlockAt(ox, baseY - 1, oz).isLiquid()) {
            baseY--;
        }
        int highest = ol.getBlockY() + 10;
        while (baseY < highest && w.getBlockAt(ox, baseY, oz).getType().isSolid()) {
            baseY++;
        }

        Material main = mat("main", Material.POLISHED_BLACKSTONE_BRICKS);
        Material accent = mat("accent", Material.OBSIDIAN);
        Material floor = mat("floor", Material.BLACKSTONE);
        Material light = mat("light", Material.SOUL_LANTERN);
        int cap = plugin.getConfig().getInt("build.max-blocks", 1500);

        pieces.sort(Comparator.comparingInt(Structures.Piece::dy)
                .thenComparingInt(Structures.Piece::dz)
                .thenComparingInt(Structures.Piece::dx));

        ArrayDeque<BTask> queue = new ArrayDeque<>();
        for (Structures.Piece pc : pieces) {
            if (queue.size() >= cap) break;
            int wx = ox + pc.dx() * rx + pc.dz() * fx;
            int wz = oz + pc.dx() * rz + pc.dz() * fz;
            int wy = baseY + pc.dy();
            if (wy < w.getMinHeight() || wy >= w.getMaxHeight()) continue;
            Material m = switch (pc.role()) {
                case MAIN -> main;
                case ACCENT -> accent;
                case FLOOR -> floor;
                case LIGHT -> light;
            };
            queue.add(new BTask(w.getBlockAt(wx, wy, wz), m));
        }
        if (queue.isEmpty()) return 0;

        List<Mob> crew = recruitCrew(owner.getLocation(), workers);
        if (crew.isEmpty()) return 0;

        BuildJob job = new BuildJob(owner.getUniqueId(), owner.getLocation(), type.label(), queue);
        ItemStack axe = manager.buildAxe();
        for (Mob m : crew) {
            takeOver(m, axe.clone());
            job.workers.add(m.getUniqueId());
        }
        register(job);
        return crew.size();
    }

    /** Stop all jobs started by this owner. Returns how many workers were freed. */
    public int stopFor(UUID owner) {
        int n = 0;
        for (Job j : new ArrayList<>(jobs)) {
            if (j.owner.equals(owner)) {
                n += j.workers.size();
                finish(j, "Stopped.");
            }
        }
        return n;
    }

    /** Stop every job. Returns how many workers were freed. */
    public int stopAll() {
        int n = 0;
        for (Job j : new ArrayList<>(jobs)) {
            n += j.workers.size();
            finish(j, null);
        }
        if (task != null) {
            task.cancel();
            task = null;
        }
        return n;
    }

    /** Called when a worker is hit or reassigned. */
    public void release(Entity e) {
        if (!(e instanceof Mob)) return;
        UUID id = e.getUniqueId();
        for (Job j : new ArrayList<>(jobs)) {
            if (j.workers.contains(id)) {
                removeWorker(j, id);
                if (j.workers.isEmpty()) finish(j, "No workers left.");
            }
        }
    }

    // ------------------------------------------------------------------
    // Job plumbing
    // ------------------------------------------------------------------

    private List<Mob> recruitCrew(Location at, int n) {
        List<Mob> out = new ArrayList<>();
        for (Mob m : manager.recruit(at, n)) {
            if (m != null && m.isValid()) out.add(m);
        }
        return out;
    }

    private void takeOver(Mob m, ItemStack tool) {
        EntityEquipment eq = m.getEquipment();
        if (eq != null) {
            saved.putIfAbsent(m.getUniqueId(), eq.getItemInMainHand().clone());
            eq.setItemInMainHand(tool);
            eq.setItemInMainHandDropChance(0f);
        }
        manager.setBusy(m, true);
        m.setTarget(null);
        manager.puppets().rebuild(m);
    }

    private void free(Mob m) {
        manager.setBusy(m, false);
        m.getPathfinder().stopPathfinding();
        ItemStack hand = saved.remove(m.getUniqueId());
        if (hand != null) {
            EntityEquipment eq = m.getEquipment();
            if (eq != null) {
                eq.setItemInMainHand(hand);
                eq.setItemInMainHandDropChance(0f);
            }
            manager.puppets().rebuild(m);
        }
    }

    private void register(Job j) {
        jobs.add(j);
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, STEP, STEP);
        }
    }

    private void removeWorker(Job j, UUID id) {
        j.workers.remove(id);
        j.forget(id);
        Entity e = Bukkit.getEntity(id);
        if (e instanceof Mob m && m.isValid()) free(m);
        else saved.remove(id);
    }

    private void finish(Job j, String msg) {
        if (j.finished) return;
        j.finished = true;
        jobs.remove(j);
        for (UUID id : new ArrayList<>(j.workers)) removeWorker(j, id);
        if (msg != null) tell(j.owner, j.label() + ": " + msg);
    }

    private void tell(UUID id, String msg) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) p.sendMessage(Component.text("[NullArmy] " + msg, NamedTextColor.GRAY));
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Job j : new ArrayList<>(jobs)) {
            if (j.finished) continue;
            if (now > j.deadline) {
                finish(j, "time limit reached.");
                continue;
            }
            for (UUID id : new ArrayList<>(j.workers)) {
                Entity e = Bukkit.getEntity(id);
                if (!(e instanceof Mob m) || !m.isValid() || !m.getWorld().equals(j.world)) {
                    removeWorker(j, id);
                }
            }
            if (j.workers.isEmpty()) {
                finish(j, "all workers lost.");
                continue;
            }
            try {
                j.tick();
            } catch (Throwable t) {
                plugin.getLogger().warning("Worker job failed: " + t);
                finish(j, "job failed.");
            }
        }
        if (jobs.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private Material mat(String key, Material def) {
        String s = plugin.getConfig().getString("build.materials." + key);
        Material m = s == null ? null : Material.matchMaterial(s);
        return m != null ? m : def;
    }

    private static Location mid(Block b) {
        return b.getLocation().add(0.5, 0.5, 0.5);
    }

    private boolean canBreak(Block b) {
        Material t = b.getType();
        if (t.isAir()) return true;
        if (b.isLiquid()) return false;
        if (t.getHardness() < 0) return false;
        if (t == Material.SPAWNER || t == Material.TRIAL_SPAWNER || t == Material.VAULT
                || t == Material.ENDER_CHEST || t == Material.REINFORCED_DEEPSLATE) return false;
        if (b.getState() instanceof Container) return false;
        for (BlockFace f : FACES) {
            if (b.getRelative(f).isLiquid()) return false;
        }
        return true;
    }

    /** Break a block for tunnelling (no item drops, so no clutter). */
    private void tunnel(Mob m, Block b) {
        if (b.getType().isAir()) return;
        Location c = mid(b);
        m.lookAt(c);
        m.swingMainHand();
        b.getWorld().spawnParticle(Particle.BLOCK, c, 12, 0.25, 0.25, 0.25, b.getBlockData());
        b.getWorld().playSound(c, b.getBlockData().getSoundGroup().getBreakSound(), 0.8f, 1f);
        b.setType(Material.AIR, true);
    }

    private boolean entityInside(Block b) {
        BoundingBox box = BoundingBox.of(b);
        for (Entity e : b.getWorld().getNearbyEntities(box)) {
            if (e instanceof LivingEntity && !(e instanceof Mannequin) && !(e instanceof ArmorStand)) {
                return true;
            }
        }
        return false;
    }

    /** A safe spot within reach of the block, or null. */
    private Location findStand(Block b) {
        for (int r = 1; r <= 2; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = 0; dy <= 2; dy++) {
                        for (int sign = 1; sign >= -1; sign -= 2) {
                            int yy = dy * sign;
                            Block feet = b.getRelative(dx, yy, dz);
                            Block head = feet.getRelative(0, 1, 0);
                            Block below = feet.getRelative(0, -1, 0);
                            if (feet.equals(b) || head.equals(b)) continue;
                            if (feet.isPassable() && head.isPassable() && !feet.isLiquid()
                                    && below.getType().isSolid() && !below.isLiquid()) {
                                return new Location(b.getWorld(), feet.getX() + 0.5, feet.getY(),
                                        feet.getZ() + 0.5);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Data classes
    // ------------------------------------------------------------------

    private static class Progress {
        Location last;
        int stuck;
        Location waypoint;
        int wpTries;

        void reset() {
            stuck = 0;
            waypoint = null;
            wpTries = 0;
        }
    }

    private static class BTask {
        final Block block;
        final Material mat;
        int tries;

        BTask(Block block, Material mat) {
            this.block = block;
            this.mat = mat;
        }
    }

    private abstract class Job {
        final UUID owner;
        final World world;
        final Location center;
        final Set<UUID> workers = new LinkedHashSet<>();
        final Map<UUID, Integer> cooldown = new HashMap<>();
        final Map<UUID, Progress> progress = new HashMap<>();
        final long deadline;
        boolean finished;

        Job(UUID owner, Location center) {
            this.owner = owner;
            this.center = center.clone();
            this.world = center.getWorld();
            this.deadline = System.currentTimeMillis() + cfg("max-minutes", 20) * 60_000L;
        }

        abstract String label();

        abstract void tick();

        void forget(UUID id) {
            cooldown.remove(id);
            progress.remove(id);
        }

        boolean ready(UUID id) {
            int cd = cooldown.getOrDefault(id, 0);
            if (cd > 0) {
                cooldown.put(id, cd - STEP);
                return false;
            }
            return true;
        }

        boolean moved(Progress p, Location cur) {
            boolean moved = p.last != null && p.last.getWorld() == cur.getWorld()
                    && p.last.distanceSquared(cur) > 0.2;
            p.last = cur.clone();
            return moved;
        }
    }

    // ------------------------------------------------------------------
    // Mining
    // ------------------------------------------------------------------

    private class MinerJob extends Job {
        final OreType type;
        final int amount;
        final int radius;
        int mined = 0;
        int scanY = Integer.MIN_VALUE;
        int emptySweeps = 0;
        final LinkedHashSet<Block> ores = new LinkedHashSet<>();
        final Set<Block> bad = new HashSet<>();
        final Map<UUID, Block> claim = new HashMap<>();

        MinerJob(UUID owner, Location center, OreType type, int amount, int radius) {
            super(owner, center);
            this.type = type;
            this.amount = amount;
            this.radius = radius;
        }

        @Override
        String label() {
            return "Mining " + type.label();
        }

        @Override
        void forget(UUID id) {
            claim.remove(id);
            super.forget(id);
        }

        @Override
        void tick() {
            scanStep();
            if (finished) return;
            for (UUID id : new ArrayList<>(workers)) {
                Entity e = Bukkit.getEntity(id);
                if (!(e instanceof Mob m)) continue;
                if (!ready(id)) continue;
                step(m);
                if (finished) return;
            }
            if (mined >= amount) {
                finish(this, "done, mined " + mined + " ore blocks.");
            }
        }

        /** Scans one horizontal layer per update so the server never lags. */
        private void scanStep() {
            int minY = Math.max(world.getMinHeight(), center.getBlockY() - radius);
            int maxY = Math.min(world.getMaxHeight() - 1, center.getBlockY() + radius);
            if (scanY == Integer.MIN_VALUE) scanY = minY;
            if (scanY > maxY) {
                if (ores.isEmpty() && claim.isEmpty()) emptySweeps++;
                else emptySweeps = 0;
                if (emptySweeps >= 2) {
                    finish(this, "no " + type.label() + " found within " + radius
                            + " blocks (mined " + mined + ").");
                    return;
                }
                scanY = minY;
            }
            int cx = center.getBlockX();
            int cz = center.getBlockZ();
            for (int bx = cx - radius; bx <= cx + radius; bx++) {
                for (int bz = cz - radius; bz <= cz + radius; bz++) {
                    if (ores.size() >= 2000) break;
                    if (!world.isChunkLoaded(bx >> 4, bz >> 4)) continue;
                    Block b = world.getBlockAt(bx, scanY, bz);
                    if (type.matches(b.getType()) && !bad.contains(b)) ores.add(b);
                }
            }
            scanY++;
        }

        private Block pick(Mob m) {
            Set<Block> claimed = new HashSet<>(claim.values());
            Location loc = m.getLocation();
            Block best = null;
            double bestD = Double.MAX_VALUE;
            Iterator<Block> it = ores.iterator();
            while (it.hasNext()) {
                Block b = it.next();
                if (!type.matches(b.getType())) {
                    it.remove();
                    continue;
                }
                if (claimed.contains(b) || bad.contains(b)) continue;
                double dx = b.getX() + 0.5 - loc.getX();
                double dy = b.getY() + 0.5 - loc.getY();
                double dz = b.getZ() + 0.5 - loc.getZ();
                double d = dx * dx + dy * dy + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = b;
                }
            }
            return best;
        }

        private boolean visible(Location eye, Location tc, Block target) {
            Vector dir = tc.toVector().subtract(eye.toVector());
            double len = dir.length();
            if (len < 0.01) return true;
            RayTraceResult r = world.rayTraceBlocks(eye, dir.normalize(), len + 0.5,
                    FluidCollisionMode.NEVER, true);
            return r != null && target.equals(r.getHitBlock());
        }

        private void step(Mob m) {
            UUID id = m.getUniqueId();
            Progress p = progress.computeIfAbsent(id, k -> new Progress());

            Block target = claim.get(id);
            if (target != null && !type.matches(target.getType())) {
                claim.remove(id);
                ores.remove(target);
                target = null;
                p.reset();
            }
            if (target == null) {
                target = pick(m);
                if (target == null) {
                    if (m.getLocation().distanceSquared(center) > 36) {
                        m.getPathfinder().moveTo(center, 1.0);
                    }
                    return;
                }
                claim.put(id, target);
                p.reset();
            }

            Location eye = m.getEyeLocation();
            Location tc = mid(target);
            if (eye.distance(tc) <= 4.5 && visible(eye, tc, target)) {
                mine(m, target);
                p.reset();
                return;
            }

            Location cur = m.getLocation();
            if (p.waypoint != null) {
                if (cur.distanceSquared(p.waypoint) < 1.2 || ++p.wpTries > 8) {
                    p.waypoint = null;
                    p.wpTries = 0;
                } else {
                    m.getPathfinder().moveTo(p.waypoint, 1.1);
                    return;
                }
            }

            boolean moved = moved(p, cur);
            boolean pathOk = m.getPathfinder().moveTo(tc, 1.1);
            if (moved) p.stuck = 0;
            else p.stuck += pathOk ? 1 : 2;

            if (p.stuck >= 3) {
                if (dig(m, target, p)) {
                    cooldown.put(id, cfg("dig-delay-ticks", 8));
                    p.stuck = 0;
                } else {
                    bad.add(target);
                    ores.remove(target);
                    claim.remove(id);
                    p.reset();
                }
            }
        }

        private void mine(Mob m, Block target) {
            Location tc = mid(target);
            m.lookAt(tc);
            m.swingMainHand();
            ItemStack tool = null;
            if (m.getEquipment() != null) tool = m.getEquipment().getItemInMainHand();
            if (tool == null || tool.getType().isAir()) tool = new ItemStack(Material.NETHERITE_PICKAXE);
            world.playSound(tc, target.getBlockData().getSoundGroup().getBreakSound(), 1f, 1f);
            target.breakNaturally(tool);
            ores.remove(target);
            claim.remove(m.getUniqueId());
            mined++;
            cooldown.put(m.getUniqueId(), cfg("mine-delay-ticks", 12));
        }

        /** Dig a 1x2 shaft toward the target. Returns false if it is unsafe or impossible. */
        private boolean dig(Mob m, Block target, Progress p) {
            Location eye = m.getEyeLocation();
            Location tc = mid(target);
            double d = eye.distance(tc);

            if (d <= 4.5) {
                Vector dir = tc.toVector().subtract(eye.toVector());
                RayTraceResult r = world.rayTraceBlocks(eye, dir.normalize(), d + 0.5,
                        FluidCollisionMode.NEVER, true);
                if (r != null && r.getHitBlock() != null && !r.getHitBlock().equals(target)) {
                    Block hit = r.getHitBlock();
                    if (!canBreak(hit)) return false;
                    tunnel(m, hit);
                }
                return true;
            }

            Block feet = m.getLocation().getBlock();
            int dx = target.getX() - feet.getX();
            int dy = target.getY() - feet.getY();
            int dz = target.getZ() - feet.getZ();
            int sx = 0;
            int sz = 0;
            if (Math.abs(dx) >= Math.abs(dz)) sx = Integer.signum(dx);
            else sz = Integer.signum(dz);

            List<Block> cols = new ArrayList<>();
            Block wp = null;
            if (sx == 0 && sz == 0) {
                cols.add(dy < 0 ? feet.getRelative(0, -1, 0) : feet.getRelative(0, 2, 0));
            } else {
                Block f = feet.getRelative(sx, 0, sz);
                if (dy < -1) {
                    cols.add(f.getRelative(0, -1, 0));
                    cols.add(f);
                    cols.add(f.getRelative(0, 1, 0));
                    wp = f.getRelative(0, -1, 0);
                } else if (dy > 1) {
                    cols.add(feet.getRelative(0, 2, 0));
                    cols.add(f.getRelative(0, 1, 0));
                    cols.add(f.getRelative(0, 2, 0));
                    wp = f.getRelative(0, 1, 0);
                } else {
                    cols.add(f);
                    cols.add(f.getRelative(0, 1, 0));
                    wp = f;
                }
            }

            for (Block b : cols) {
                if (!canBreak(b)) return false;
            }
            for (Block b : cols) tunnel(m, b);
            if (wp != null) {
                p.waypoint = new Location(world, wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5);
                p.wpTries = 0;
            }
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    private class BuildJob extends Job {
        final String name;
        final ArrayDeque<BTask> queue;
        final Map<UUID, BTask> assigned = new HashMap<>();
        int placed = 0;

        BuildJob(UUID owner, Location center, String name, ArrayDeque<BTask> queue) {
            super(owner, center);
            this.name = name;
            this.queue = queue;
        }

        @Override
        String label() {
            return "Building " + name;
        }

        @Override
        void forget(UUID id) {
            BTask t = assigned.remove(id);
            if (t != null) queue.addFirst(t);
            super.forget(id);
        }

        @Override
        void tick() {
            for (UUID id : new ArrayList<>(workers)) {
                Entity e = Bukkit.getEntity(id);
                if (!(e instanceof Mob m)) continue;
                if (!ready(id)) continue;
                step(m);
            }
            if (!finished && queue.isEmpty() && assigned.isEmpty()) {
                finish(this, "finished, placed " + placed + " blocks.");
            }
        }

        private void step(Mob m) {
            UUID id = m.getUniqueId();
            BTask t = assigned.get(id);
            if (t == null) {
                t = queue.pollFirst();
                if (t == null) return;
                assigned.put(id, t);
                progress.remove(id);
            }

            Block b = t.block;
            Material cur = b.getType();
            if (cur == t.mat) {
                assigned.remove(id);
                return;
            }
            if (!(cur.isAir() || (b.isReplaceable() && !b.isLiquid()))) {
                assigned.remove(id); // occupied by something else, skip it
                return;
            }

            Location eye = m.getEyeLocation();
            Location bc = mid(b);
            if (eye.distance(bc) <= 4.5) {
                if (entityInside(b)) {
                    assigned.remove(id);
                    if (++t.tries < 40) queue.addLast(t);
                    cooldown.put(id, 8);
                    return;
                }
                m.lookAt(bc);
                m.swingMainHand();
                b.setType(t.mat, true);
                world.playSound(bc, b.getBlockData().getSoundGroup().getPlaceSound(), 1f, 1f);
                placed++;
                assigned.remove(id);
                progress.remove(id);
                cooldown.put(id, cfg("build-delay-ticks", 6));
                return;
            }

            Progress p = progress.computeIfAbsent(id, k -> new Progress());
            boolean moved = moved(p, m.getLocation());
            boolean ok = m.getPathfinder().moveTo(bc, 1.2);
            if (moved && ok) p.stuck = 0;
            else p.stuck += ok ? 1 : 2;

            if (p.stuck >= 2) {
                Location from = m.getLocation().add(0, 1, 0);
                Location stand = findStand(b);
                if (stand == null) stand = center.clone();
                world.spawnParticle(Particle.REVERSE_PORTAL, from, 20, 0.3, 0.6, 0.3, 0.1);
                m.teleport(stand);
                world.spawnParticle(Particle.REVERSE_PORTAL, stand.clone().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.1);
                p.stuck = 0;
                p.last = null;
            }
        }
    }
}
