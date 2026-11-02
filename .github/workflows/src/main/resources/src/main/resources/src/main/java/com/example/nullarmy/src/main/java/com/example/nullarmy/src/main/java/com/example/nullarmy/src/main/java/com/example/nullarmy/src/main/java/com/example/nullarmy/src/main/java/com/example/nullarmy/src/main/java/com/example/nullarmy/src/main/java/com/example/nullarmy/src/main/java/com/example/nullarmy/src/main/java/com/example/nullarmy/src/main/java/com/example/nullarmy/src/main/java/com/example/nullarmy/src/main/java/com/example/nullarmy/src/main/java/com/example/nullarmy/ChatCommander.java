
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Lets ops command the army by typing normally in chat. */
public class ChatCommander implements Listener {

    // ------------------------------------------------------------------
    // Vocabulary
    // ------------------------------------------------------------------
    private static Set<String> set(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }

    private static final Map<String, String> VERBS = new HashMap<>();
    private static final Map<String, Formation> FORMS = new HashMap<>();
    private static final Map<String, Integer> NUMS = new HashMap<>();
    private static final Map<String, Material> ALIAS = new HashMap<>();

    private static final Set<String> GIVE_STRONG = set("give", "gimme", "bring", "hand", "fetch", "toss", "pass");
    private static final Set<String> GIVE_WEAK = set("get", "need", "want");
    private static final Set<String> GIVE_SKIP = set("me", "us", "my", "i", "some", "x", "plenty");

    private static final Set<String> FILLER = set("the", "a", "an", "please", "pls", "plz", "now", "just",
            "can", "could", "you", "u", "would", "will", "ok", "okay", "hey", "yo", "immediately", "quickly",
            "fast", "asap", "of", "to", "for", "at", "on", "with", "your", "our", "lets", "let", "ground",
            "position", "spot", "out", "over", "and", "do");

    private static final Set<String> COMMON = set("like", "there", "here", "that", "this", "what", "with",
            "from", "then", "when", "want", "need", "have", "hello", "thanks", "thank", "good", "great", "nice",
            "lets", "okay", "soon", "over", "also", "back", "near", "away", "again", "around", "first", "next",
            "after", "before", "while", "them", "they", "your", "were", "been", "will", "just", "know", "think",
            "really", "dont", "does", "done", "some", "more", "very", "much", "many", "make", "made", "look",
            "talk", "walk", "well", "work", "time", "game", "play", "kill", "base", "cant", "wont", "says",
            "said", "tell", "told", "left", "right", "high", "long", "wide", "size");

    private static final Set<String> LOOK = set("that", "there", "look", "it", "him", "her", "them", "this");
    private static final Set<String> WORKER_WORDS = set("worker", "miner", "builder", "soldier", "guy", "man",
            "men", "boy", "bot", "unit");
    private static final Set<String> RANKS = set("soldier", "hunter", "archer", "general", "warlord", "random");
    private static final Set<String> NEG = set("dont", "never", "not", "no");
    private static final Set<String> RADIUS_BEFORE = set("within", "radius", "range", "around");
    private static final Set<String> RADIUS_AFTER = set("block", "radius", "range", "meter", "away");
    private static final Set<String> STRICT = set("strike", "portal", "promote", "attack", "mine", "build",
            "spawn", "formation", "guard", "follow", "standdown", "negate");

    private static final List<String> PRIORITY = List.of("strike", "portal", "clear", "promote", "stop",
            "standdown", "attack", "mine", "build", "spawn", "hold", "formation", "guard", "follow", "goto",
            "help");

    private static final String[] REPLIES = {"Understood.", "On it.", "For NULL.", "Yes, leader.",
            "It will be done.", "Moving out."};

    private static final Set<String> VOCAB = new HashSet<>();
    private static final List<String> FUZZ = new ArrayList<>();

    private static void verbs(String intent, String... words) {
        for (String w : words) VERBS.put(w, intent);
    }

    private static void forms(Formation f, String... words) {
        for (String w : words) FORMS.put(w, f);
    }

    private static void nums(int v, String... words) {
        for (String w : words) NUMS.put(w, v);
    }

    static {
        verbs("strike", "strike", "bomb", "nuke", "orbital", "cannon", "smite", "obliterate");
        verbs("portal", "portal", "portals", "reinforcement", "reinforcements", "backup");
        verbs("clear", "dismiss", "clear", "disband", "vanish", "despawn");
        verbs("promote", "promote", "elevate", "upgrade");
        verbs("stop", "stop", "halt", "cease", "cancel", "abort");
        verbs("standdown", "standdown", "ceasefire", "peace", "retreat");
        verbs("attack", "attack", "kill", "hunt", "destroy", "assault", "engage", "eliminate", "murder",
                "slay", "charge", "raid", "fight", "rush");
        verbs("mine", "mine", "dig", "harvest", "excavate", "quarry");
        verbs("build", "build", "construct", "erect", "fortify");
        verbs("spawn", "spawn", "recruit", "summon", "conjure", "create");
        verbs("formation", "form", "formation", "arrange", "organize", "align", "assume");
        verbs("guard", "guard", "protect", "defend", "escort", "surround", "cover");
        verbs("follow", "follow", "regroup", "assemble", "rally", "come", "heel", "join", "fall", "fallin");
        verbs("hold", "hold", "stay", "wait", "remain");
        verbs("goto", "goto", "go", "move", "advance", "march", "walk", "proceed", "push", "head", "travel");
        verbs("help", "help", "commands", "orders", "cmds");

        forms(Formation.RING, "ring", "circle", "round", "orb");
        forms(Formation.LINE, "line", "row");
        forms(Formation.COLUMN, "column", "file", "single", "snake");
        forms(Formation.WEDGE, "wedge", "vee", "arrow", "spearhead", "triangle");
        forms(Formation.SQUARE, "square", "box", "rectangle");
        forms(Formation.PHALANX, "phalanx", "shield");
        forms(Formation.BLOCK, "block", "blob", "cluster", "clump", "mass", "crowd", "pack");

        nums(0, "zero");
        nums(1, "one");
        nums(2, "two", "couple");
        nums(3, "three", "few");
        nums(4, "four");
        nums(5, "five");
        nums(6, "six");
        nums(7, "seven");
        nums(8, "eight");
        nums(9, "nine");
        nums(10, "ten");
        nums(11, "eleven");
        nums(12, "twelve", "dozen");
        nums(13, "thirteen");
        nums(14, "fourteen");
        nums(15, "fifteen");
        nums(16, "sixteen");
        nums(17, "seventeen");
        nums(18, "eighteen");
        nums(19, "nineteen");
        nums(20, "twenty");
        nums(30, "thirty");
        nums(40, "forty");
        nums(50, "fifty");
        nums(60, "sixty");
        nums(70, "seventy");
        nums(80, "eighty");
        nums(90, "ninety");
        nums(100, "hundred");

        alias("iron", Material.IRON_INGOT);
        alias("gold", Material.GOLD_INGOT);
        alias("copper", Material.COPPER_INGOT);
        alias("netherite", Material.NETHERITE_INGOT);
        alias("debris", Material.ANCIENT_DEBRIS);
        alias("lapis", Material.LAPIS_LAZULI);
        alias("pearl", Material.ENDER_PEARL);
        alias("pearls", Material.ENDER_PEARL);
        alias("shell", Material.SHULKER_SHELL);
        alias("shulker", Material.SHULKER_SHELL);
        alias("gapple", Material.GOLDEN_APPLE);
        alias("godapple", Material.ENCHANTED_GOLDEN_APPLE);
        alias("xp", Material.EXPERIENCE_BOTTLE);
        alias("steak", Material.COOKED_BEEF);
        alias("crystal", Material.END_CRYSTAL);
        alias("log", Material.OAK_LOG);
        alias("wood", Material.OAK_LOG);
        alias("plank", Material.OAK_PLANKS);
        alias("planks", Material.OAK_PLANKS);
        alias("totem", Material.TOTEM_OF_UNDYING);

        VOCAB.addAll(VERBS.keySet());
        VOCAB.addAll(FORMS.keySet());
        VOCAB.addAll(GIVE_STRONG);
        VOCAB.addAll(GIVE_WEAK);
        VOCAB.addAll(RANKS);
        VOCAB.addAll(WORKER_WORDS);
        VOCAB.addAll(LOOK);
        VOCAB.addAll(set("stand", "down", "up", "coal", "iron", "copper", "gold", "redstone", "lapis", "lazuli",
                "diamond", "emerald", "quartz", "debris", "netherite", "ancient", "scrap", "ore", "all",
                "everything", "dia", "wall", "tower", "watchtower", "spire", "outpost", "fort", "fortress",
                "base", "camp", "keep", "platform", "pad", "floor", "plate", "stack", "stacks", "within",
                "radius", "range", "around", "meter", "away"));

        Set<String> fuzzTargets = new HashSet<>();
        fuzzTargets.addAll(VERBS.keySet());
        fuzzTargets.addAll(FORMS.keySet());
        fuzzTargets.addAll(RANKS);
        fuzzTargets.addAll(set("coal", "iron", "copper", "gold", "redstone", "lapis", "diamond", "emerald",
                "quartz", "debris", "netherite", "wall", "tower", "outpost", "platform", "fortress"));
        for (String w : fuzzTargets) {
            if (w.length() >= 4) FUZZ.add(w);
        }
    }

    private static void alias(String k, Material m) {
        ALIAS.put(k, m);
    }

    // ------------------------------------------------------------------
    private final NullArmy plugin;
    private final ArmyManager manager;
    private final Random rnd = new Random();

    private static class State {
        boolean any;
        boolean silent;
        String reply;
    }

    public ChatCommander(NullArmy plugin, ArmyManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ------------------------------------------------------------------
    // Chat hook
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) return;
        Player p = e.getPlayer();
        if (!p.isOp()) return;
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        Bukkit.getScheduler().runTask(plugin, () -> handle(p, text));
    }

    private void handle(Player p, String text) {
        if (!p.isOnline()) return;
        boolean leader = manager.isLeader(p);
        if (plugin.getConfig().getBoolean("chat.require-totem", false) && !leader) return;

        String lower = text.toLowerCase(Locale.ROOT).trim();
        String rest = stripPrefix(lower);
        boolean prefixed = rest != null;
        if (!prefixed) {
            boolean allowed = leader || !plugin.getConfig().getBoolean("chat.require-prefix", true);
            if (!allowed) return;
            rest = lower;
        }
        if (rest.isBlank()) return;
        boolean strict = !prefixed;

        State st = new State();
        for (List<String> seg : splitThen(tokenize(rest))) {
            runSegment(p, seg, strict, st);
        }
        if (st.silent) return;
        if (st.any) speak(p, st.reply != null ? st.reply : REPLIES[rnd.nextInt(REPLIES.length)]);
        else if (prefixed) speak(p, "I do not understand, leader.");
    }

    private String stripPrefix(String lower) {
        String s = lower;
        for (String lead : List.of("hey ", "yo ", "ok ", "okay ")) {
            if (s.startsWith(lead)) {
                s = s.substring(lead.length()).trim();
                break;
            }
        }
        for (String pre : plugin.getConfig().getStringList("chat.prefixes")) {
            String pl = pre.toLowerCase(Locale.ROOT).trim();
            if (pl.isEmpty() || !s.startsWith(pl)) continue;
            int n = pl.length();
            if (s.length() == n || !Character.isLetterOrDigit(s.charAt(n))) {
                return s.substring(n).replaceFirst("^[\\s,:;.!\\-]+", "");
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Tokenizing and grammar
    // ------------------------------------------------------------------
    private List<String> tokenize(String s) {
        s = s.replace("'", "").replace("\u2019", "");
        s = s.replaceAll("[,;.!?]+", " then ");
        s = s.replaceAll("[^a-z0-9 ]", " ");
        List<String> out = new ArrayList<>();
        for (String t : s.trim().split("\\s+")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private List<List<String>> splitThen(List<String> tokens) {
        List<List<String>> out = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (String t : tokens) {
            if (t.equals("then")) {
                if (!cur.isEmpty()) out.add(cur);
                cur = new ArrayList<>();
            } else {
                cur.add(t);
            }
        }
        if (!cur.isEmpty()) out.add(cur);
        for (List<String> l : out) {
            while (!l.isEmpty() && l.get(l.size() - 1).equals("and")) l.remove(l.size() - 1);
        }
        return out;
    }

    private List<List<String>> splitAnd(List<String> seg) {
        List<List<String>> out = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (int i = 0; i < seg.size(); i++) {
            String t = seg.get(i);
            if (t.equals("and") && !cur.isEmpty() && i + 1 < seg.size() && isVerbish(seg.get(i + 1))) {
                out.add(cur);
                cur = new ArrayList<>();
                continue;
            }
            cur.add(t);
        }
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }

    private boolean isVerbish(String t) {
        String s = stem(t);
        return s != null && (VERBS.containsKey(s) || GIVE_STRONG.contains(s) || GIVE_WEAK.contains(s));
    }

    private boolean isNumber(String t) {
        return t.matches("\\d+");
    }

    private int toInt(String t) {
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException ex) {
            return 99999;
        }
    }

    private List<String> convertNumbers(List<String> in) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            String t = in.get(i);
            Integer v = NUMS.get(t);
            if (v == null) {
                out.add(t);
                continue;
            }
            int total = v;
            if (total < 10 && i + 1 < in.size() && "hundred".equals(in.get(i + 1))) {
                total *= 100;
                i++;
            } else if (total >= 20 && total < 100 && total % 10 == 0 && i + 1 < in.size()) {
                Integer nx = NUMS.get(in.get(i + 1));
                if (nx != null && nx >= 1 && nx <= 9) {
                    total += nx;
                    i++;
                }
            }
            out.add(String.valueOf(total));
        }
        return out;
    }

    /** Strips -ing / -s / -ed endings. Returns the vocabulary word or null. */
    private String stem(String t) {
        if (VOCAB.contains(t)) return t;
        List<String> c = new ArrayList<>();
        int n = t.length();
        if (t.endsWith("ing") && n >= 5) {
            String b = t.substring(0, n - 3);
            c.add(b);
            c.add(b + "e");
            if (b.length() > 2 && b.charAt(b.length() - 1) == b.charAt(b.length() - 2)) {
                c.add(b.substring(0, b.length() - 1));
            }
        }
        if (t.endsWith("ed") && n > 4) {
            c.add(t.substring(0, n - 2));
            c.add(t.substring(0, n - 1));
        }
        if (t.endsWith("es") && n > 4) c.add(t.substring(0, n - 2));
        if (t.endsWith("s") && n > 3) c.add(t.substring(0, n - 1));
        for (String s : c) {
            if (VOCAB.contains(s)) return s;
        }
        return null;
    }

    private static int osa(String a, String b) {
        int n = a.length();
        int m = b.length();
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[n][m];
    }

    private String fuzzy(String t) {
        int limit = t.length() >= 7 ? 2 : 1;
        String best = null;
        int bd = 99;
        boolean tie = false;
        for (String w : FUZZ) {
            if (w.charAt(0) != t.charAt(0)) continue;
            if (Math.abs(w.length() - t.length()) > limit) continue;
            int d = osa(t, w);
            if (d > limit) continue;
            if (d < bd) {
                bd = d;
                best = w;
                tie = false;
            } else if (d == bd && !w.equals(best)) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    private List<String> normalize(List<String> clause, boolean strict) {
        List<String> out = new ArrayList<>();
        for (String t : convertNumbers(clause)) {
            if (FILLER.contains(t)) continue;
            if (isNumber(t) || VOCAB.contains(t)) {
                out.add(t);
                continue;
            }
            if (findPlayer(t) != null) {
                out.add(t);
                continue;
            }
            String s = stem(t);
            if (s != null) {
                out.add(s);
                continue;
            }
            if (!strict && t.length() >= 4 && !COMMON.contains(t)) {
                String f = fuzzy(t);
                if (f != null) {
                    out.add(f);
                    continue;
                }
            }
            out.add(t);
        }
        return out;
    }

    private Player findPlayer(String t) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(t)) return p;
        }
        if (t.length() < 4) return null;
        Player found = null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(t)) {
                if (found != null) return null;
                found = p;
            }
        }
        return found;
    }

    // ------------------------------------------------------------------
    // Running clauses
    // ------------------------------------------------------------------
    private void runSegment(Player p, List<String> seg, boolean strict, State st) {
        for (List<String> clause : splitAnd(seg)) {
            if (tryGive(p, clause)) {
                st.any = true;
                st.silent = true;
                continue;
            }
            List<String> norm = normalize(clause, strict);
            if (interpret(p, norm, strict, st)) st.any = true;
        }
    }

    private void run(Player p, String args) {
        p.performCommand("nullarmy " + args);
    }

    private boolean interpret(Player p, List<String> toks, boolean strict, State st) {
        if (toks.isEmpty()) return false;
        Set<String> has = new HashSet<>();
        Formation form = null;
        boolean neg = false;
        for (String t : toks) {
            String v = VERBS.get(t);
            if (v != null) has.add(v);
            if (form == null && FORMS.containsKey(t)) form = FORMS.get(t);
            if (NEG.contains(t)) neg = true;
        }
        if (toks.contains("stand") && toks.contains("down")) has.add("standdown");

        String intent = null;
        if (neg && (has.contains("attack") || has.contains("strike") || has.contains("mine")
                || has.contains("build") || has.contains("portal") || has.contains("spawn"))) {
            intent = "negate";
        } else {
            for (String k : PRIORITY) {
                if (has.contains(k)) {
                    intent = k;
                    break;
                }
            }
            if (intent == null && form != null && !strict) intent = "formation";
        }
        if (intent == null) return false;
        if (strict && !STRICT.contains(intent)) return false;
        if (strict && intent.equals("follow")
                && !(toks.contains("follow") || toks.contains("regroup") || toks.contains("fall")
                || toks.contains("fallin"))) {
            return false;
        }

        switch (intent) {
            case "strike" -> run(p, "strike");
            case "portal" -> {
                int n = firstNumber(toks);
                run(p, n > 0 ? "portal " + Math.min(n, 30) : "portal");
            }
            case "clear" -> run(p, "clear");
            case "promote" -> {
                int n = firstNumber(toks);
                run(p, n > 0 ? "promote " + Math.min(n, 20) : "promote");
            }
            case "stop" -> {
                run(p, "standdown");
                run(p, "stop");
            }
            case "standdown", "negate" -> {
                run(p, "standdown");
                run(p, "stop");
            }
            case "attack" -> attack(p, toks, st);
            case "mine" -> mine(p, toks, st);
            case "build" -> build(p, toks, st);
            case "spawn" -> spawn(p, toks);
            case "hold" -> {
                run(p, "hold");
                st.reply = "Holding this ground.";
            }
            case "formation" -> {
                if (form == null) {
                    if (toks.contains("up")) {
                        run(p, "standdown");
                        run(p, "stop");
                        run(p, "follow");
                        st.reply = "Falling in.";
                    } else {
                        run(p, "formation");
                        st.reply = "Which formation, leader?";
                    }
                } else {
                    String name = form.name().toLowerCase(Locale.ROOT);
                    run(p, "formation " + name);
                    st.reply = "Forming " + name + ".";
                }
            }
            case "guard" -> {
                run(p, "formation ring");
                run(p, "follow");
                st.reply = "Forming ring.";
            }
            case "follow" -> {
                run(p, "standdown");
                run(p, "stop");
                run(p, "follow");
                st.reply = "Falling in.";
            }
            case "goto" -> {
                run(p, "goto");
                st.reply = "Advancing.";
            }
            case "help" -> {
                help(p);
                st.silent = true;
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private int firstNumber(List<String> toks) {
        for (String t : toks) {
            if (isNumber(t)) return toInt(t);
        }
        return -1;
    }

    private void attack(Player p, List<String> toks, State st) {
        Player target = null;
        boolean look = false;
        int count = -1;
        for (String t : toks) {
            if (isNumber(t)) {
                if (count < 0) count = Math.min(toInt(t), 100);
                continue;
            }
            if (LOOK.contains(t)) {
                look = true;
                continue;
            }
            if (VOCAB.contains(t)) continue;
            Player f = findPlayer(t);
            if (f != null && target == null) target = f;
        }
        if (target == null && !look) {
            speak(p, "Who is the target?");
            st.silent = true;
            return;
        }
        String suffix = count > 0 ? " " + count : "";
        if (target != null) run(p, "attack " + target.getName() + suffix);
        else run(p, "attack look" + suffix);
    }

    private void mine(Player p, List<String> toks, State st) {
        OreType ore = null;
        Integer workers = null;
        Integer amount = null;
        Integer radius = null;
        List<Integer> loose = new ArrayList<>();
        for (int i = 0; i < toks.size(); i++) {
            String t = toks.get(i);
            if (ore == null && !isNumber(t)) {
                OreType o = OreType.parse(t);
                if (o != null) {
                    ore = o;
                    continue;
                }
            }
            if (!isNumber(t)) continue;
            int n = toInt(t);
            String next = i + 1 < toks.size() ? toks.get(i + 1) : "";
            String prev = i > 0 ? toks.get(i - 1) : "";
            if (WORKER_WORDS.contains(next)) workers = n;
            else if (RADIUS_BEFORE.contains(prev) || RADIUS_AFTER.contains(next)) radius = n;
            else if (!next.isEmpty() && OreType.parse(next) != null) amount = n;
            else loose.add(n);
        }
        for (int n : loose) {
            if (workers == null) workers = n;
            else if (amount == null) amount = n;
            else if (radius == null) radius = n;
        }
        if (ore == null) {
            speak(p, "What should I mine?");
            st.silent = true;
            return;
        }
        run(p, "mine " + ore.label() + " " + (workers == null ? 3 : workers) + " "
                + (amount == null ? 16 : amount) + " " + (radius == null ? 16 : radius));
    }

    private void build(Player p, List<String> toks, State st) {
        Structures.Type type = null;
        Integer workers = null;
        Integer size = null;
        for (int i = 0; i < toks.size(); i++) {
            String t = toks.get(i);
            if (type == null && !isNumber(t)) {
                Structures.Type ty = Structures.Type.parse(t);
                if (ty != null) {
                    type = ty;
                    continue;
                }
            }
            if (!isNumber(t)) continue;
            int n = toInt(t);
            String next = i + 1 < toks.size() ? toks.get(i + 1) : "";
            if (WORKER_WORDS.contains(next)) workers = n;
            else if (size == null) size = n;
        }
        if (type == null) {
            speak(p, "What should I build? A wall, tower, outpost or platform?");
            st.silent = true;
            return;
        }
        String cmd = "build " + type.label() + " " + (workers == null ? 4 : workers);
        if (size != null) cmd += " " + size;
        run(p, cmd);
    }

    private void spawn(Player p, List<String> toks) {
        int n = 5;
        boolean gotN = false;
        String rank = "random";
        for (String t : toks) {
            if (isNumber(t) && !gotN) {
                n = Math.max(1, Math.min(100, toInt(t)));
                gotN = true;
            } else if (RANKS.contains(t)) {
                rank = t.equals("warlord") ? "general" : t;
            }
        }
        run(p, "spawn " + n + " " + rank);
    }

    private void help(Player p) {
        String[] lines = {
                "Orders (start with null/army/soldiers/general, or hold the Totem):",
                "  form a wedge / ring / line / column / square / phalanx / block",
                "  guard me, follow me, hold here, go there",
                "  attack <player>, attack that, kill <player> with 20",
                "  mine 5 diamonds, mine iron with 8 miners within 24 blocks",
                "  build a tower / wall / outpost / platform",
                "  spawn 10 generals, promote 2, strike, portal 8",
                "  stop, stand down, dismiss",
                "  give me 3 stacks of iron, get me a netherite pickaxe"
        };
        for (String l : lines) p.sendMessage(Component.text(l, NamedTextColor.DARK_GRAY));
    }

    // ------------------------------------------------------------------
    // Giving items
    // ------------------------------------------------------------------
    private boolean tryGive(Player p, List<String> clause) {
        if (!plugin.getConfig().getBoolean("give.enabled", true)) return false;

        int vi = -1;
        boolean weak = false;
        for (int i = 0; i < clause.size(); i++) {
            if (GIVE_STRONG.contains(clause.get(i))) {
                vi = i;
                break;
            }
        }
        if (vi < 0) {
            for (int i = 0; i < clause.size(); i++) {
                if (GIVE_WEAK.contains(clause.get(i))) {
                    vi = i;
                    weak = true;
                    break;
                }
            }
        }
        if (vi < 0) return false;

        if (weak) {
            for (String t : clause) {
                String s = stem(t);
                if (s != null && VERBS.containsKey(s)) return false;
            }
        }

        int amount = -1;
        boolean stack = false;
        List<String> words = new ArrayList<>();
        for (String t : convertNumbers(clause.subList(vi + 1, clause.size()))) {
            if (FILLER.contains(t) || GIVE_SKIP.contains(t)) continue;
            if (t.matches("\\d+x")) t = t.substring(0, t.length() - 1);
            if (isNumber(t)) {
                if (amount < 0) amount = toInt(t);
            } else if (t.equals("stack") || t.equals("stacks")) {
                stack = true;
            } else {
                words.add(t);
            }
        }
        if (words.isEmpty()) return false;

        // The real Totem of NULL
        if (words.contains("totem") && words.contains("null")) {
            Mob soldier = nearestSoldier(p);
            if (needsSoldier() && soldier == null) {
                noSoldier(p);
                return true;
            }
            run(p, "totem " + p.getName());
            speakAs(p, soldier, "Here, leader. The Totem of NULL.");
            return true;
        }

        // NULL's own tools
        List<String> sing = new ArrayList<>();
        for (String w : words) sing.add(w.length() > 3 && w.endsWith("s") ? w.substring(0, w.length() - 1) : w);
        boolean toolWords = true;
        for (String w : sing) {
            if (!(w.equals("null") || w.equals("pickaxe") || w.equals("axe"))) toolWords = false;
        }
        ItemStack proto = null;
        String label = null;
        if (toolWords && sing.contains("pickaxe")) {
            proto = manager.buildPickaxe();
            label = "NULL pickaxe";
        } else if (toolWords && sing.contains("axe")) {
            proto = manager.buildAxe();
            label = "NULL axe";
        }

        if (proto == null) {
            Material mat = resolve(words);
            if (mat == null) return false;
            proto = new ItemStack(mat);
            label = mat.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }

        Mob soldier = nearestSoldier(p);
        if (needsSoldier() && soldier == null) {
            noSoldier(p);
            return true;
        }

        int ms = proto.getMaxStackSize();
        int max = plugin.getConfig().getInt("give.max-amount", 2304);
        int n = amount < 0 ? 1 : amount;
        if (stack) n *= ms;
        if (ms == 1) n = Math.min(n, 36);
        n = Math.max(1, Math.min(max, n));

        giveItems(p, proto, n);
        handOver(p, soldier, n, label);
        return true;
    }

    private boolean needsSoldier() {
        return plugin.getConfig().getBoolean("give.needs-soldier", true);
    }

    private void noSoldier(Player p) {
        p.sendMessage(Component.text("No NULL soldier is near enough to hear you.", NamedTextColor.GRAY));
    }

    private Mob nearestSoldier(Player p) {
        Mob best = null;
        double bd = 64 * 64;
        for (Mob m : p.getWorld().getEntitiesByClass(Mob.class)) {
            if (!manager.isNullMob(m) || !m.isValid()) continue;
            double d = m.getLocation().distanceSquared(p.getLocation());
            if (d <= bd) {
                bd = d;
                best = m;
            }
        }
        return best;
    }

    private Material resolve(List<String> words) {
        int n = words.size();
        for (int start = 0; start < n; start++) {
            for (int end = n; end > start; end--) {
                String name = String.join("_", words.subList(start, end));
                Material m = material(name);
                if (m != null) return m;
            }
        }
        return null;
    }

    private Material material(String name) {
        Material m = lookup(name);
        if (m != null) return m;
        if (name.endsWith("ies") && name.length() > 4) {
            m = lookup(name.substring(0, name.length() - 3) + "y");
            if (m != null) return m;
        }
        if (name.endsWith("es") && name.length() > 3) {
            m = lookup(name.substring(0, name.length() - 2));
            if (m != null) return m;
        }
        if (name.endsWith("s") && name.length() > 2) {
            m = lookup(name.substring(0, name.length() - 1));
            if (m != null) return m;
        }
        return null;
    }

    private Material lookup(String name) {
        Material a = ALIAS.get(name);
        if (a != null) return a;
        Material m = Material.matchMaterial(name);
        if (m != null && m.isItem() && !m.isAir()) return m;
        return null;
    }

    private void giveItems(Player p, ItemStack proto, int total) {
        int remaining = total;
        int ms = Math.max(1, proto.getMaxStackSize());
        while (remaining > 0) {
            int amt = Math.min(remaining, ms);
            ItemStack st = proto.clone();
            st.setAmount(amt);
            Map<Integer, ItemStack> left = p.getInventory().addItem(st);
            for (ItemStack extra : left.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), extra);
            }
            remaining -= amt;
        }
    }

    private void handOver(Player p, Mob soldier, int n, String label) {
        String line = "Here, leader. " + n + "x " + label + ".";
        if (soldier == null) {
            p.sendMessage(Component.text(line, NamedTextColor.DARK_GRAY));
            return;
        }
        soldier.lookAt(p.getEyeLocation());
        soldier.swingMainHand();
        if (soldier.getWorld().equals(p.getWorld())) {
            Location a = soldier.getEyeLocation();
            Location b = p.getEyeLocation().subtract(0, 0.3, 0);
            Vector d = b.toVector().subtract(a.toVector());
            double len = d.length();
            if (len > 0.1) {
                int steps = (int) (len / 0.5);
                d.normalize().multiply(0.5);
                Location c = a.clone();
                for (int i = 0; i < steps; i++) {
                    c.add(d);
                    p.getWorld().spawnParticle(Particle.REVERSE_PORTAL, c, 2, 0.02, 0.02, 0.02, 0);
                }
            }
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.2f);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
        speakAs(p, soldier, line);
    }

    // ------------------------------------------------------------------
    // Replies
    // ------------------------------------------------------------------
    private void speak(Player p, String line) {
        Mob best = null;
        double bestScore = Double.MAX_VALUE;
        for (Mob m : p.getWorld().getEntitiesByClass(Mob.class)) {
            if (!manager.isNullMob(m) || !m.isValid()) continue;
            double d = m.getLocation().distance(p.getLocation());
            double score = d + (manager.rankOf(m) == NullRank.GENERAL ? 0 : 1_000_000);
            if (score < bestScore) {
                bestScore = score;
                best = m;
            }
        }
        if (best == null) return;
        speakAs(p, best, line);
    }

    private void speakAs(Player p, Mob m, String line) {
        Component name = (m != null && m.customName() != null) ? m.customName() : Component.text("NULL");
        p.sendMessage(Component.text("<", NamedTextColor.DARK_GRAY)
                .append(name)
                .append(Component.text("> " + line, NamedTextColor.DARK_GRAY)));
    }
}
