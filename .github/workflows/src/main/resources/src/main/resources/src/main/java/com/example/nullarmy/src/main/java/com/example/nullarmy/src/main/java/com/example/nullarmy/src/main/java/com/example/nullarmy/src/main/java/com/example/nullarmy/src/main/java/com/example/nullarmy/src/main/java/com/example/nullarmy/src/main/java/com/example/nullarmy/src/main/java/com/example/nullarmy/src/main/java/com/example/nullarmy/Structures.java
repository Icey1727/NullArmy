package com.example.nullarmy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Block layouts for the builders.
 * Local coordinates: x = left/right (centered), y = up, z = forward (away from the player).
 */
public final class Structures {

    private Structures() {}

    public enum Role { MAIN, ACCENT, FLOOR, LIGHT }

    public record Piece(int dx, int dy, int dz, Role role) {}

    public enum Type {
        WALL(3, 40, 12),
        TOWER(6, 40, 16),
        OUTPOST(7, 25, 11),
        PLATFORM(3, 40, 11);

        private final int min;
        private final int max;
        private final int def;

        Type(int min, int max, int def) {
            this.min = min;
            this.max = max;
            this.def = def;
        }

        /** size <= 0 means "use the default". */
        public int clamp(int size) {
            if (size <= 0) return def;
            return Math.max(min, Math.min(max, size));
        }

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Returns null if unknown. */
        public static Type parse(String s) {
            if (s == null) return null;
            return switch (s.toLowerCase(Locale.ROOT).trim()) {
                case "wall", "walls" -> WALL;
                case "tower", "towers", "watchtower", "spire" -> TOWER;
                case "outpost", "fort", "fortress", "base", "camp", "keep" -> OUTPOST;
                case "platform", "pad", "floor", "plate" -> PLATFORM;
                default -> null;
            };
        }
    }

    private static final class Layout {
        private final Map<Long, Piece> map = new LinkedHashMap<>();

        void put(int x, int y, int z, Role r) {
            long key = ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (long) (z + 512);
            map.put(key, new Piece(x, y, z, r));
        }

        List<Piece> list() {
            return new ArrayList<>(map.values());
        }
    }

    public static List<Piece> generate(Type type, int size) {
        int s = type.clamp(size);
        return switch (type) {
            case WALL -> wall(s);
            case TOWER -> tower(s);
            case OUTPOST -> outpost(s);
            case PLATFORM -> platform(s);
        };
    }

    private static List<Piece> wall(int len) {
        Layout l = new Layout();
        int half = len / 2;
        for (int i = 0; i < len; i++) {
            int x = i - half;
            boolean pillar = i % 5 == 0 || i == len - 1;
            for (int z = 0; z <= 1; z++) {
                for (int y = 0; y <= 4; y++) {
                    l.put(x, y, z, pillar ? Role.ACCENT : Role.MAIN);
                }
                if (pillar) l.put(x, 5, z, Role.ACCENT);
                else if (i % 2 == 0) l.put(x, 5, z, Role.MAIN);
            }
            if (pillar) l.put(x, 6, 0, Role.LIGHT);
        }
        return l.list();
    }

    private static List<Piece> tower(int h) {
        Layout l = new Layout();
        for (int y = 0; y < h; y++) {
            for (int x = -2; x <= 2; x++) {
                for (int z = 0; z <= 4; z++) {
                    boolean edge = Math.abs(x) == 2 || z == 0 || z == 4;
                    boolean corner = Math.abs(x) == 2 && (z == 0 || z == 4);
                    boolean floor = y == 0 || y == h - 1 || y % 6 == 0;
                    if (floor) {
                        l.put(x, y, z, edge ? (corner ? Role.ACCENT : Role.MAIN) : Role.FLOOR);
                        continue;
                    }
                    if (!edge) continue;
                    if (x == 0 && z == 0 && y <= 2) continue; // door
                    if (y % 6 == 3 && ((x == 0 && z == 4) || (z == 2 && Math.abs(x) == 2))) continue; // windows
                    l.put(x, y, z, corner ? Role.ACCENT : Role.MAIN);
                }
            }
        }
        // battlements
        for (int x = -2; x <= 2; x++) {
            for (int z = 0; z <= 4; z++) {
                boolean edge = Math.abs(x) == 2 || z == 0 || z == 4;
                if (!edge) continue;
                boolean corner = Math.abs(x) == 2 && (z == 0 || z == 4);
                if (corner) {
                    l.put(x, h, z, Role.ACCENT);
                    l.put(x, h + 1, z, Role.LIGHT);
                } else if (Math.floorMod(x + z, 2) == 0) {
                    l.put(x, h, z, Role.MAIN);
                }
            }
        }
        return l.list();
    }

    private static List<Piece> outpost(int s) {
        if (s % 2 == 0) s++;
        int half = s / 2;
        Layout l = new Layout();
        for (int x = -half; x <= half; x++) {
            for (int z = 0; z < s; z++) {
                boolean edge = Math.abs(x) == half || z == 0 || z == s - 1;
                boolean corner = Math.abs(x) == half && (z == 0 || z == s - 1);
                l.put(x, 0, z, edge ? Role.MAIN : Role.FLOOR);
                if (!edge) continue;
                if (z == 0 && Math.abs(x) <= 1) continue; // gate
                for (int y = 1; y <= 4; y++) {
                    l.put(x, y, z, corner ? Role.ACCENT : Role.MAIN);
                }
                if (corner) {
                    l.put(x, 5, z, Role.ACCENT);
                    l.put(x, 6, z, Role.ACCENT);
                    l.put(x, 7, z, Role.LIGHT);
                } else if (Math.floorMod(x + z, 2) == 0) {
                    l.put(x, 5, z, Role.MAIN);
                }
            }
        }
        int cz = s / 2;
        for (int y = 1; y <= 4; y++) l.put(0, y, cz, Role.ACCENT);
        l.put(0, 5, cz, Role.LIGHT);
        return l.list();
    }

    private static List<Piece> platform(int s) {
        int lo = -(s / 2);
        int hi = lo + s - 1;
        Layout l = new Layout();
        for (int x = lo; x <= hi; x++) {
            for (int z = 0; z < s; z++) {
                boolean edge = x == lo || x == hi || z == 0 || z == s - 1;
                boolean corner = (x == lo || x == hi) && (z == 0 || z == s - 1);
                Role r = edge ? Role.ACCENT : (Math.floorMod(x + z, 2) == 0 ? Role.MAIN : Role.FLOOR);
                l.put(x, 0, z, r);
                if (corner) l.put(x, 1, z, Role.LIGHT);
            }
        }
        return l.list();
    }
}
