package com.example.nullarmy;

import java.util.concurrent.ThreadLocalRandom;

public enum NullRank {
    SOLDIER(40, 0.25, 48, -1),
    HUNTER(50, 0.32, 64, -1),
    ARCHER(35, 0.26, 48, -1),
    GENERAL(150, 0.28, 64, 12);

    public final double health;
    public final double speed;
    public final double follow;
    public final double damage;   // -1 = leave default

    NullRank(double health, double speed, double follow, double damage) {
        this.health = health;
        this.speed = speed;
        this.follow = follow;
        this.damage = damage;
    }

    public static NullRank parse(String s) {
        if (s == null) return null;
        return switch (s.toLowerCase()) {
            case "soldier", "soldiers" -> SOLDIER;
            case "hunter", "hunters" -> HUNTER;
            case "archer", "archers" -> ARCHER;
            case "general", "generals", "warlord", "warlords" -> GENERAL;
            default -> null;
        };
    }

    public static NullRank random() {
        int r = ThreadLocalRandom.current().nextInt(100);
        if (r < 50) return SOLDIER;
        if (r < 75) return HUNTER;
        if (r < 90) return ARCHER;
        return GENERAL;
    }
}
