package com.example.nullarmy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class NullCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("spawn", "clear", "totem", "strike", "portal",
            "mine", "build", "attack", "standdown", "stop", "formation", "hold", "goto", "follow",
            "promote", "reload");
    private static final List<String> RANKS = List.of("soldier", "hunter", "archer", "general", "random");

    private final NullArmy plugin;
    private final ArmyManager manager;

    public NullCommand(NullArmy plugin, ArmyManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private void msg(CommandSender s, String text) {
        s.sendMessage(Component.text("[NullArmy] " + text, NamedTextColor.GRAY));
    }

    private void err(CommandSender s, String text) {
        s.sendMessage(Component.text("[NullArmy] " + text, NamedTextColor.RED));
    }

    private Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        err(s, "This command must be run by a player.");
        return null;
    }

    private int num(String[] a, int i, int def, int min, int max) {
        if (i >= a.length) return def;
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(a[i])));
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private NullRank parseRank(String s) {
        try {
            return NullRank.parse(s);
        } catch (Throwable t) {
            return null;
        }
    }

    private Formation parseFormation(String s) {
        for (Formation f : Formation.values()) {
            if (f.name().equalsIgnoreCase(s)) return f;
        }
        return null;
    }

    private Location lookLocation(Player p) {
        Block b = p.getTargetBlockExact(150);
        if (b == null) return null;
        return b.getLocation().add(0.5, 1, 0.5);
    }

    private void stopFighting() {
        manager.clearOrders();
        manager.coordinator().reset();
        for (World w : Bukkit.getWorlds()) {
            for (Mob m : w.getEntitiesByClass(Mob.class)) {
                if (!manager.isNullMob(m) || manager.isBusy(m)) continue;
                m.setTarget(null);
                m.getPathfinder().stopPathfinding();
            }
        }
    }

    // ------------------------------------------------------------------
    // Command
    // ------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.isOp()) {
            s.sendMessage("§cOnly operators can use NullArmy commands.");
            return true;
        }
        if (a.length == 0) {
            msg(s, "/nullarmy <" + String.join("|", SUBS) + ">");
            return true;
        }

        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> {
                Player p = player(s);
                if (p == null) return true;
                int amt = num(a, 1, 1, 1, 100);
                NullRank rank = null;
                if (a.length > 2 && !a[2].equalsIgnoreCase("random")) {
                    rank = parseRank(a[2]);
                    if (rank == null) {
                        err(s, "Unknown rank. Use soldier, hunter, archer, general or random.");
                        return true;
                    }
                }
                Location loc = p.getLocation();
                if (rank == null) {
                    for (int i = 0; i < amt; i++) manager.spawnAround(loc, 1, NullRank.random(), 4, 10);
                } else {
                    manager.spawnAround(loc, amt, rank, 4, 10);
                }
                msg(s, "Spawned " + amt + " NULL.");
            }
            case "clear" -> {
                plugin.manager().workers().stopAll();
                manager.clear();
                msg(s, "All NULL have been removed.");
            }
            case "totem" -> {
                Player target = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player pp ? pp : null);
                if (target == null) {
                    err(s, "Player not found.");
                    return true;
                }
                manager.giveTotem(target);
                msg(s, "Gave the Totem of NULL to " + target.getName() + ".");
            }
            case "strike" -> {
                Player p = player(s);
                if (p == null) return true;
                Location l = lookLocation(p);
                if (l == null) {
                    err(s, "Look at a block (within 150 blocks).");
                    return true;
                }
                manager.orbitalStrike(l);
                msg(s, "Orbital strike launched.");
            }
            case "portal" -> {
                Player p = player(s);
                if (p == null) return true;
                int amt = num(a, 1, 5, 1, 30);
                manager.portalStrike(p.getLocation(), amt);
                msg(s, "Portal opened.");
            }
            case "mine" -> {
                Player p = player(s);
                if (p == null) return true;
                if (a.length < 2) {
                    err(s, "/nullarmy mine <ore> [workers] [amount] [radius]");
                    return true;
                }
                OreType ore = OreType.parse(a[1]);
                if (ore == null) {
                    err(s, "Unknown ore.");
                    return true;
                }
                int workers = num(a, 2, 3, 1, 20);
                int amount = num(a, 3, 16, 1, 10000);
                int radius = num(a, 4, 16, 4, 32);
                int n = manager.workers().startMining(p, ore, workers, amount, radius);
                if (n <= 0) err(s, "Could not start mining.");
                else msg(s, n + " workers are mining " + ore.label() + ".");
            }
            case "build" -> {
                Player p = player(s);
                if (p == null) return true;
                if (a.length < 2) {
                    err(s, "/nullarmy build <wall|tower|outpost|platform> [workers] [size]");
                    return true;
                }
                Structures.Type type = Structures.Type.parse(a[1]);
                if (type == null) {
                    err(s, "Unknown structure.");
                    return true;
                }
                int workers = num(a, 2, 4, 1, 20);
                int size = num(a, 3, 0, 0, 100);
                int n = manager.workers().startBuilding(p, type, workers, size);
                if (n <= 0) err(s, "Could not start building.");
                else msg(s, n + " workers are building a " + type.label() + ".");
            }
            case "attack" -> {
                if (a.length < 2) {
                    err(s, "/nullarmy attack <player|look> [count]");
                    return true;
                }
                int count = num(a, 2, 10, 1, 100);
                LivingEntity target;
                Location origin;
                if (a[1].equalsIgnoreCase("look")) {
                    Player p = player(s);
                    if (p == null) return true;
                    Entity t = p.getTargetEntity(100);
                    if (!(t instanceof LivingEntity le)) {
                        err(s, "Look at a living target.");
                        return true;
                    }
                    target = le;
                    origin = p.getLocation();
                } else {
                    Player tp = Bukkit.getPlayerExact(a[1]);
                    if (tp == null) {
                        err(s, "Player not found.");
                        return true;
                    }
                    target = tp;
                    origin = s instanceof Player pp ? pp.getLocation() : tp.getLocation();
                }
                if (manager.isNullMob(target)) {
                    err(s, "That is one of NULL.");
                    return true;
                }
                if (target instanceof Player tp2 && !manager.isValidTarget(tp2)) {
                    err(s, "That player cannot be targeted (the leader is never attacked).");
                    return true;
                }
                int n = manager.orderAttack(origin, target, count);
                msg(s, n + " soldiers ordered to attack.");
            }
            case "standdown" -> {
                stopFighting();
                msg(s, "The army stands down.");
            }
            case "stop" -> {
                int n;
                if (s instanceof Player p) n = manager.workers().stopFor(p.getUniqueId());
                else n = manager.workers().stopAll();
                msg(s, "Stopped " + n + " workers.");
            }
            case "formation" -> {
                if (a.length < 2) {
                    msg(s, "Current formation: " + manager.follower().formation().name().toLowerCase(Locale.ROOT)
                            + ". Options: " + String.join(", ", formationNames()));
                    return true;
                }
                Formation f = parseFormation(a[1]);
                if (f == null) {
                    err(s, "Unknown formation. Options: " + String.join(", ", formationNames()));
                    return true;
                }
                manager.follower().setFormation(f);
                msg(s, "Formation: " + f.name().toLowerCase(Locale.ROOT) + ".");
            }
            case "hold" -> {
                Player p = player(s);
                if (p == null) return true;
                manager.follower().holdAt(p.getLocation());
                msg(s, "Holding this position.");
            }
            case "goto" -> {
                Player p = player(s);
                if (p == null) return true;
                Location l = lookLocation(p);
                if (l == null) {
                    err(s, "Look at a block (within 150 blocks).");
                    return true;
                }
                manager.follower().holdAt(l);
                msg(s, "Advancing to the marked position.");
            }
            case "follow" -> {
                manager.follower().follow();
                msg(s, "The army follows the Totem holder.");
            }
            case "promote" -> {
                Player p = player(s);
                if (p == null) return true;
                int n = manager.lore().promoteNearest(p.getLocation(), num(a, 1, 1, 1, 20));
                msg(s, "Promoted " + n + " to Commander.");
            }
            case "reload" -> {
                plugin.reloadConfig();
                msg(s, "Config reloaded.");
            }
            default -> err(s, "Unknown subcommand.");
        }
        return true;
    }

    private List<String> formationNames() {
        return Arrays.stream(Formation.values()).map(f -> f.name().toLowerCase(Locale.ROOT)).toList();
    }

    // ------------------------------------------------------------------
    // Tab completion
    // ------------------------------------------------------------------
    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        if (!s.isOp()) return List.of();
        List<String> options = new ArrayList<>();
        if (a.length == 1) {
            options.addAll(SUBS);
        } else if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "spawn" -> options.addAll(List.of("1", "5", "10", "25", "50"));
                case "totem" -> Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
                case "attack" -> {
                    options.add("look");
                    Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
                }
                case "mine" -> Arrays.stream(OreType.values()).forEach(o -> options.add(o.label()));
                case "build" -> Arrays.stream(Structures.Type.values()).forEach(t -> options.add(t.label()));
                case "formation" -> options.addAll(formationNames());
                case "promote", "portal" -> options.addAll(List.of("1", "3", "5", "10"));
                default -> { }
            }
        } else if (a.length == 3 && a[0].equalsIgnoreCase("spawn")) {
            options.addAll(RANKS);
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(last)) out.add(o);
        }
        return out;
    }
}
