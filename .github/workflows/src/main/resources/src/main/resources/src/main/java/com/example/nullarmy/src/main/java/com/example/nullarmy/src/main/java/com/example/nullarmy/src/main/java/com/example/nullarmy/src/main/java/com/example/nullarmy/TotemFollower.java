package com.example.nullarmy;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public class TotemFollower {

    private final NullArmy plugin;
    private final ArmyManager manager;
    private final NamespacedKey mobKey;

    private Formation formation;
    private Location anchor = null;   // null = follow the Totem holder
    private final Map<UUID, Location> lastPos = new HashMap<>();
    private final Map<UUID, Float> facing = new HashMap<>();

    public TotemFollower(NullArmy plugin, ArmyManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.mobKey = new NamespacedKey(plugin, "null_mob");
        Formation f = Formation.parse(plugin.getConfig().getString("formation.default", "ring"));
        this.formation = f == null ? Formation.RING : f;
    }

    public Formation formation() { return formation; }
    public void setFormation(Formation f) { this.formation = f; }
    public void holdAt(Location l) { this.anchor = l.clone(); }
    public void follow() { this.anchor = null; }
    public boolean holding() { return anchor != null; }

    public void tick() {
        FileConfiguration c = plugin.getConfig();
        if (!c.getBoolean("follow.enabled", true)) return;
        double sp = c.getDouble("formation.spacing", 2.2);
        double tpDist = c.getDouble("follow.teleport-distance", 40);
        boolean cross = c.getBoolean("follow.cross-world", true);

        List<Player> holders = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isDead() && manager.isLeader(p)) holders.add(p);
        }
        if (anchor == null && holders.isEmpty()) return;

        // work out which way each holder is travelling
        for (Player h : holders) {
            UUID id = h.getUniqueId();
            Location now = h.getLocation();
            Location last = lastPos.get(id);
            if (last == null || last.getWorld() != now.getWorld()) {
                lastPos.put(id, now);
                facing.put(id, now.getYaw());
            } else {
                double dx = now.getX() - last.getX(), dz = now.getZ() - last.getZ();
                if (dx * dx + dz * dz > 1.0) {
                    facing.put(id, (float) Math.toDegrees(Math.atan2(-dx, dz)));
                    lastPos.put(id, now);
                }
            }
        }

        Map<World, List<Mob>> byWorld = new HashMap<>();
        for (World w : Bukkit.getWorlds()) {
            for (Mob m : w.getEntitiesByClass(Mob.class)) {
                if (!m.getPersistentDataContainer().has(mobKey, PersistentDataType.STRING)) continue;
                if (m.getTarget() != null || manager.orderOf(m) != null || manager.isBusy(m)) continue;
                byWorld.computeIfAbsent(w, k -> new ArrayList<>()).add(m);
            }
        }

        for (Map.Entry<World, List<Mob>> en : byWorld.entrySet()) {
            World w = en.getKey();
            List<Mob> list = en.getValue();
            Location center;
            float yaw;
            if (anchor != null) {
                if (anchor.getWorld() != w) continue;
                center = anchor;
                yaw = anchor.getYaw();
            } else {
                Player h = holderIn(holders, w);
                if (h == null) {
                    if (!cross) continue;
                    Location hl = holders.get(0).getLocation();
                    for (Mob m : list) m.teleport(hl);   // sorted into formation next cycle
                    continue;
                }
                center = h.getLocation();
                yaw = facing.getOrDefault(h.getUniqueId(), center.getYaw());
            }

            list.sort(Comparator.<Mob>comparingInt(this::row).thenComparing(Mob::getUniqueId));
            int n = list.size();
            for (int i = 0; i < n; i++) {
                march(list.get(i), place(center, yaw, formation.slot(i, n, sp)), tpDist);
            }
        }
    }

    private Player holderIn(List<Player> holders, World w) {
        for (Player p : holders) if (p.getWorld() == w) return p;
        return null;
    }

    private int row(Mob m) {
        String r = m.getPersistentDataContainer().get(mobKey, PersistentDataType.STRING);
        if (r == null) return 4;
        return switch (r.toLowerCase()) {
            case "general", "warlord" -> 0;
            case "soldier" -> 1;
            case "hunter" -> 2;
            case "archer" -> 3;
            default -> 4;
        };
    }

    private Location place(Location c, float yaw, double[] s) {
        double r = Math.toRadians(yaw);
        double fx = -Math.sin(r), fz = Math.cos(r);       // forward
        double rx = -Math.cos(r), rz = -Math.sin(r);      // right
        return new Location(c.getWorld(),
                c.getX() + rx * s[0] - fx * s[1],
                c.getY(),
                c.getZ() + rz * s[0] - fz * s[1], yaw, 0);
    }

    private void march(Mob m, Location slot, double tpDist) {
        double d = m.getLocation().distance(slot);
        if (d > tpDist) { m.teleport(slot); return; }
        if (d > 1.3) {
            var a = m.getAttribute(Attribute.MOVEMENT_SPEED);
            double base = a == null ? 0.25 : a.getBaseValue();
            // equal real pace for every rank, faster when lagging behind
            double mult = (0.25 / base) * (1 + Math.min(d / 12.0, 0.6));
            m.getPathfinder().moveTo(slot, mult);
        } else {
            m.getPathfinder().stopPathfinding();
            m.setRotation(slot.getYaw(), 0);
        }
    }
}
