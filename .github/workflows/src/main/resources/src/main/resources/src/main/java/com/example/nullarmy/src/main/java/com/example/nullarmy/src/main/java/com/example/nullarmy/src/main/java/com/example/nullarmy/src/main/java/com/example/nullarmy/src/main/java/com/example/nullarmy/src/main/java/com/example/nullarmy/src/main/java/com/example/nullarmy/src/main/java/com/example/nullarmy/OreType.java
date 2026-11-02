package com.example.nullarmy;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Ore groups the miners can target. */
public enum OreType {
    COAL(Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE),
    IRON(Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE),
    COPPER(Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE),
    GOLD(Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.NETHER_GOLD_ORE),
    REDSTONE(Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE),
    LAPIS(Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE),
    DIAMOND(Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE),
    EMERALD(Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE),
    QUARTZ(Material.NETHER_QUARTZ_ORE),
    DEBRIS(Material.ANCIENT_DEBRIS),
    ALL();

    private static final Set<Material> ALL_ORES = buildAll();

    private final Set<Material> materials = EnumSet.noneOf(Material.class);

    OreType(Material... mats) {
        for (Material m : mats) materials.add(m);
    }

    private static Set<Material> buildAll() {
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (OreType t : values()) set.addAll(t.materials);
        return set;
    }

    public boolean matches(Material m) {
        if (this == ALL) return ALL_ORES.contains(m);
        return materials.contains(m);
    }

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Returns null if the word is not an ore. */
    public static OreType parse(String s) {
        if (s == null) return null;
        return switch (s.toLowerCase(Locale.ROOT).trim()) {
            case "coal" -> COAL;
            case "iron" -> IRON;
            case "copper" -> COPPER;
            case "gold" -> GOLD;
            case "redstone", "red" -> REDSTONE;
            case "lapis", "lazuli" -> LAPIS;
            case "diamond", "diamonds", "dia" -> DIAMOND;
            case "emerald", "emeralds" -> EMERALD;
            case "quartz" -> QUARTZ;
            case "debris", "netherite", "ancient", "ancientdebris", "scrap" -> DEBRIS;
            case "all", "any", "everything", "ore", "ores" -> ALL;
            default -> null;
        };
    }
}
