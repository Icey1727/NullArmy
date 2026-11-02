package com.example.nullarmy;

public enum Formation {
    RING, LINE, COLUMN, WEDGE, SQUARE, PHALANX, BLOCK;

    public static Formation parse(String s) {
        if (s == null) return null;
        return switch (s.toLowerCase()) {
            case "ring", "circle", "round", "surround" -> RING;
            case "line", "row" -> LINE;
            case "column", "file" -> COLUMN;
            case "wedge", "vee", "arrow" -> WEDGE;
            case "square", "box" -> SQUARE;
            case "phalanx", "shield", "wall" -> PHALANX;
            case "block", "ranks", "grid" -> BLOCK;
            default -> null;
        };
    }

    /** Returns {right, back} for slot i of n. Negative back = in front of the leader. */
    public double[] slot(int i, int n, double sp) {
        return switch (this) {
            case LINE -> new double[]{(i - (n - 1) / 2.0) * sp, 3};
            case COLUMN -> new double[]{0, 3 + i * sp};
            case WEDGE -> {
                if (i == 0) yield new double[]{0, 2.5};
                int row = (i + 1) / 2;
                double side = (i % 2 == 1) ? -1 : 1;
                yield new double[]{side * row * sp, 2.5 + row * sp};
            }
            case SQUARE -> {
                double half = Math.max(3, n * sp / 8.0);
                double t = i * (8 * half) / n;
                int edge = (int) (t / (2 * half));
                double f = t - edge * 2 * half;
                yield switch (edge % 4) {
                    case 0 -> new double[]{-half + f, -half};
                    case 1 -> new double[]{half, -half + f};
                    case 2 -> new double[]{half - f, half};
                    default -> new double[]{-half, half - f};
                };
            }
            case PHALANX -> {
                int w = Math.max(1, (int) Math.ceil(Math.sqrt(n * 2.0)));
                int col = i % w, row = i / w;
                yield new double[]{(col - (w - 1) / 2.0) * 1.5, -3 - row * 1.5};
            }
            case BLOCK -> new double[]{((i % 5) - 2) * sp, 3 + (i / 5) * sp};
            default -> {
                double r = Math.max(3.5, n * sp / (2 * Math.PI));
                double a = 2 * Math.PI * i / n;
                yield new double[]{Math.cos(a) * r, Math.sin(a) * r};
            }
        };
    }
}
