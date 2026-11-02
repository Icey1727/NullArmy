package com.example.nullarmy;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

/** Squad AI: shared targets, staging, coordinated charges, flanking, rallying and retreating. */
public class Coordinator {

    private record Prov(UUID attacker, long until) {}

    private final NullArmy plugin;
    private final ArmyManager manager;

    private final Map<UUID, Prov> provoked = new HashMap<>();   // soldier -> who hit it
    private final Map<UUID, UUID> engaged = new HashMap<>();    // soldier -> target it is working on
    private final Set<UUID> charging = new HashSet<>();
    private final Set<UUID> retreating = new HashSet<>();
    private final Map<UUID, Long> rallyCd = new HashMap<>();    // general -> next rally time

    private double linkDist, assaultRatio, staging, archerDist, alertRadius, retreatHp, detect;
    private long rallyMs;

    public Coordinator(NullArmy plugin, ArmyManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ---------- public API ----------

    /** A NULL soldier was hit: it and its nearby friends go after the attacker. */
    public void provoke(Mob victim, LivingEntity attacker) {
        if (attacker == null || manager.isNullMob(attacker)) return;
        if (attacker instanceof Player p && !manager.isValidTarget(p)) return;
        Prov pv = new Prov(attacker.getUniqueId(), System.currentTimeMillis() + 15_000L);
        provoked.put(victim.getUniqueId(), pv);
        double r = plugin.getConfig().getDouble("coordination.alert-radius", 64);
        for (Entity e : victim.getNearbyEntities(r, r, r)) {
            if (e instanceof Mob o && manager.isNullMob(o) && !manager.isBusy(o)) {
                provoked.put(o.getUniqueId(), pv);
            }
        }
    }

    public void reset() {
        provoked.clear();
        engaged.clear();
        charging.clear();
        retreating.clear();
        rallyCd.clear();
    }

    public void tick() {
        readConfig();
        long now = System.currentTimeMillis();

        List<Mob> free = new ArrayList<>();
        Set<UUID> alive = new HashSet<>();
        for (Mob m : manager.allMobs()) {
            if (!m.isValid() || m.isDead()) continue;
            alive.add(m.getUniqueId());
            if (manager.isBusy(m)) continue;
            free.add(m);
        }
        charging.retainAll(alive);
        retreating.retainAll(alive);
        provoked.keySet().retainAll(alive);
        engaged.keySet().retainAll(alive);
        rallyCd.keySet().retainAll(alive);

        boolean holderOnline = false;
        if (plugin.getConfig().getBoolean("follow.enabled", true)) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.isDead() && manager.isLeader(p)) { holderOnline = true; break; }
            }
        }

        for (List<Mob> squad : squads(free)) runSquad(squad, now, holderOnline);
    }

    // ---------- setup ----------

    private void readConfig() {
        FileConfiguration c = plugin.getConfig();
        linkDist = c.getDouble("coordination.squad-link-distance", 20);
        assaultRatio = c.getDouble("coordination.assault-ratio", 0.7);
        staging = c.getDouble("coordination.staging-distance", 14);
        archerDist = c.getDouble("coordination.archer-distance", 8);
        alertRadius = c.getDouble("coordination.alert-radius", 64);
        retreatHp = c.getDouble("coordination.retreat-health", 0.25);
        rallyMs = (long) (c.getDouble("coordination.rally-cooldown-seconds", 20) * 1000);
        detect = c.getDouble("coordination.detect-range", 40);
    }

    /** Groups soldiers that are close to each other (chains of neighbours). */
    private List<List<Mob>> squads(List<Mob> all) {
        List<List<Mob>> out = new ArrayList<>();
        Set<Mob> seen = new HashSet<>();
        double link2 = linkDist * linkDist;
        for (Mob start : all) {
            if (!seen.add(start)) continue;
            List<Mob> group = new ArrayList<>();
            Deque<Mob> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                Mob cur = queue.poll();
                group.add(cur);
                for (Mob o : all) {
                    if (seen.contains(o) || o.getWorld() != cur.getWorld()) continue;
                    if (o.getLocation().distanceSquared(cur.getLocation()) <= link2) {
                        seen.add(o);
                        queue.add(o);
                    }
                }
            }
            out.add(group);
        }
        return out;
    }

    // ---------- one squad ----------

    private void runSquad(List<Mob> squad, long now, boolean holderOnline) {
        Mob leader = pickLeader(squad);

        List<Mob> fighters = new ArrayList<>();
        for (Mob m : squad) {
            if (m != leader && handleRetreat(m, leader)) continue;
            fighters.add(m);
        }
        if (fighters.isEmpty()) return;

        // forced targets: attack orders and whoever hit us
        Map<UUID, LivingEntity> forced = new HashMap<>();
        for (Mob m : fighters) {
            LivingEntity f = forcedTarget(m, now);
            if (f != null) forced.put(m.getUniqueId(), f);
        }

        LivingEntity shared = sharedTarget(fighters);

        // should the whole squad charge now?
        boolean squadCharge = false;
        if (shared != null) {
            int total = 0, near = 0;
            double closest = Double.MAX_VALUE;
            for (Mob m : fighters) {
                if (isArcher(m)) continue;
                total++;
                if (charging.contains(m.getUniqueId())) squadCharge = true;
                if (m.getWorld() != shared.getWorld()) continue;
                double d = m.getLocation().distance(shared.getLocation());
                closest = Math.min(closest, d);
                if (d <= 18) near++;
            }
            if (!forced.isEmpty() || total <= 1 || closest <= 10
                    || (total > 0 && near / (double) total >= assaultRatio)) {
                squadCharge = true;
            }
        }

        List<Mob> idle = new ArrayList<>();
        for (Mob m : fighters) {
            UUID id = m.getUniqueId();
            LivingEntity t = forced.get(id);
            boolean isForced = t != null;
            if (t == null) t = shared;

            if (t == null || t.getWorld() != m.getWorld()) {
                charging.remove(id);
                engaged.remove(id);
                if (m.getTarget() != null) m.setTarget(null);
                idle.add(m);
                continue;
            }
            engaged.put(id, t.getUniqueId());

            if (isArcher(m)) {
                archer(m, t);
            } else if (isForced || squadCharge) {
                charging.add(id);
                charge(m, t);
            } else {
                stage(m, t);
            }

            if (manager.rankOf(m) == NullRank.GENERAL && m.getLocation().distance(t.getLocation()) <= 30) {
                rally(m, now);
            }
        }

        if (!idle.isEmpty() && !holderOnline) formUp(idle, leader);
    }

    // ---------- targets ----------

    private LivingEntity forcedTarget(Mob m, long now) {
        UUID id = m.getUniqueId();

        UUID ord = manager.orderOf(m);
        if (ord != null) {
            Entity e = Bukkit.getEntity(ord);
            if (e instanceof LivingEntity le && validTarget(le)) return le;
            manager.dropOrder(m);
        }

        Prov p = provoked.get(id);
        if (p != null) {
            if (p.until() < now) {
                provoked.remove(id);
            } else {
                Entity e = Bukkit.getEntity(p.attacker());
                if (e instanceof LivingEntity le && validTarget(le) && le.getWorld() == m.getWorld()
                        && le.getLocation().distance(m.getLocation()) <= alertRadius) {
                    return le;
                }
                provoked.remove(id);
            }
        }
        return null;
    }

    /** The target most of the squad is already on, else the nearest visible player. */
    private LivingEntity sharedTarget(List<Mob> fighters) {
        Map<UUID, Integer> votes = new HashMap<>();
        for (Mob m : fighters) {
            UUID u = engaged.get(m.getUniqueId());
            if (u != null) votes.merge(u, 1, Integer::sum);
        }
        UUID bestId = null;
        int bestVotes = 0;
        for (Map.Entry<UUID, Integer> e : votes.entrySet()) {
            if (e.getValue() > bestVotes) { bestVotes = e.getValue(); bestId = e.getKey(); }
        }
        if (bestId != null) {
            Entity e = Bukkit.getEntity(bestId);
            if (e instanceof LivingEntity le && validTarget(le)) return le;
        }

        LivingEntity best = null;
        double bestD = detect * detect;
        for (Mob m : fighters) {
            for (Player p : m.getWorld().getPlayers()) {
                if (!manager.isValidTarget(p)) continue;
                double d = p.getLocation().distanceSquared(m.getLocation());
                if (d >= bestD) continue;
                if (!m.hasLineOfSight(p)) continue;
                best = p;
                bestD = d;
            }
        }
        return best;
    }

    private boolean validTarget(LivingEntity t) {
        if (t == null || !t.isValid() || t.isDead()) return false;
        if (manager.isNullMob(t)) return false;
        if (t instanceof Player p) return manager.isValidTarget(p);
        return true;
    }

    // ---------- behaviours ----------

    private void charge(Mob m, LivingEntity t) {
        double d = m.getLocation().distance(t.getLocation());
        if (manager.rankOf(m) == NullRank.HUNTER && d > 9) {
            m.setTarget(null);
            m.getPathfinder().moveTo(flankPoint(m, t), 1.2);
            return;
        }
        if (m.getTarget() != t) m.setTarget(t);
    }

    private Location flankPoint(Mob m, LivingEntity t) {
        Location tl = t.getLocation();
        Vector dir = tl.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(1, 0, 0);
        dir.normalize();
        Vector side = new Vector(-dir.getZ(), 0, dir.getX());
        double s = (m.getUniqueId().hashCode() & 1) == 0 ? 3 : -3;
        return tl.clone().add(dir.clone().multiply(-4)).add(side.multiply(s));
    }

    /** Wait at a ring around the target until the squad is ready to charge together. */
    private void stage(Mob m, LivingEntity t) {
        if (m.getTarget() != null) m.setTarget(null);
        Location ml = m.getLocation(), tl = t.getLocation();
        double d = ml.distance(tl);
        if (d > staging + 3) {
            m.getPathfinder().moveTo(pointAway(tl, ml, staging), 1.1);
        } else {
            m.getPathfinder().stopPathfinding();
            m.lookAt(t);
        }
    }

    private void archer(Mob m, LivingEntity t) {
        Location ml = m.getLocation(), tl = t.getLocation();
        double d = ml.distance(tl);
        if (d < archerDist) {
            m.setTarget(t);
            m.getPathfinder().moveTo(pointAway(ml, tl, 6), 1.2);   // back off
        } else if (d <= 28) {
            m.setTarget(t);
            if (!m.hasLineOfSight(t)) m.getPathfinder().moveTo(tl, 1.0);
        } else {
            if (m.getTarget() != null) m.setTarget(null);
            m.getPathfinder().moveTo(pointAway(tl, ml, 22), 1.1);
        }
    }

    /** A point `dist` blocks from `base`, in the direction of `toward`. */
    private Location pointAway(Location base, Location toward, double dist) {
        Vector v = toward.toVector().subtract(base.toVector()).setY(0);
        if (v.lengthSquared() < 1e-4) v = new Vector(1, 0, 0);
        v.normalize().multiply(dist);
        Location out = base.clone().add(v);
        out.setY(toward.getY());
        return out;
    }

    private void rally(Mob general, long now) {
        UUID id = general.getUniqueId();
        Long next = rallyCd.get(id);
        if (next != null && now < next) return;
        rallyCd.put(id, now + rallyMs);
        general.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 200, 0));
        for (Entity e : general.getNearbyEntities(24, 24, 24)) {
            if (e instanceof LivingEntity le && manager.isNullMob(le)) {
                le.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 200, 0));
            }
        }
        general.getWorld().playSound(general.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 0.8f);
    }

    /** Returns true if this soldier is currently falling back and should skip fighting. */
    private boolean handleRetreat(Mob m, Mob leader) {
        UUID id = m.getUniqueId();
        double ratio = ratio(m);

        if (retreating.contains(id)) {
            if (ratio >= 0.6 || !m.hasPotionEffect(PotionEffectType.REGENERATION)) {
                retreating.remove(id);
                return false;
            }
            m.setTarget(null);
            if (m.getWorld() == leader.getWorld()
                    && m.getLocation().distanceSquared(leader.getLocation()) > 9) {
                m.getPathfinder().moveTo(leader.getLocation(), 1.3);
            } else {
                m.getPathfinder().stopPathfinding();
            }
            return true;
        }

        if (ratio < retreatHp) {
            retreating.add(id);
            charging.remove(id);
            engaged.remove(id);
            m.setTarget(null);
            m.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 400, 2));
            return true;
        }
        return false;
    }

    // ---------- formation while idle ----------

    private void formUp(List<Mob> idle, Mob leader) {
        idle.remove(leader);
        if (idle.isEmpty()) return;
        idle.sort(Comparator.<Mob>comparingInt(this::row).thenComparing(Mob::getUniqueId));

        Location ll = leader.getLocation();
        Vector fwd = ll.getDirection().setY(0);
        if (fwd.lengthSquared() < 1e-4) fwd = new Vector(0, 0, 1);
        fwd.normalize();
        Vector right = new Vector(-fwd.getZ(), 0, fwd.getX());

        for (int i = 0; i < idle.size(); i++) {
            Mob m = idle.get(i);
            if (m.getWorld() != leader.getWorld()) continue;
            int r = i / 3, col = i % 3 - 1;
            Location slot = ll.clone()
                    .add(fwd.clone().multiply(-(2.5 + r * 2.2)))
                    .add(right.clone().multiply(col * 2.2));
            if (m.getLocation().distance(slot) > 2.5) {
                m.getPathfinder().moveTo(slot, 1.0);
            } else {
                m.getPathfinder().stopPathfinding();
            }
        }
    }

    // ---------- helpers ----------

    private Mob pickLeader(List<Mob> squad) {
        Mob best = null;
        for (Mob m : squad) {
            if (manager.rankOf(m) != NullRank.GENERAL) continue;
            if (best == null || m.getHealth() > best.getHealth()) best = m;
        }
        if (best != null) return best;
        for (Mob m : squad) {
            if (best == null || m.getHealth() > best.getHealth()) best = m;
        }
        return best;
    }

    private boolean isArcher(Mob m) {
        return manager.rankOf(m) == NullRank.ARCHER;
    }

    private int row(Mob m) {
        NullRank r = manager.rankOf(m);
        if (r == null) return 4;
        return switch (r) {
            case GENERAL -> 0;
            case SOLDIER -> 1;
            case HUNTER -> 2;
            case ARCHER -> 3;
        };
    }

    private double ratio(Mob m) {
        AttributeInstance max = m.getAttribute(Attribute.MAX_HEALTH);
        double mx = max == null ? 20 : max.getValue();
        return mx <= 0 ? 1 : m.getHealth() / mx;
    }
}
