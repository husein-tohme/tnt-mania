package net.husein.tntmania.catalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the table of TNT types (tnt-types.txt). The mod and the program that draws the pictures both read it
 * through this class, so the two can never disagree. No game code is used here.
 */
public final class Catalog {
    public static final String MOD = "tntmania";
    public static final int EXPECTED = 250;
    private static final String FILE = "/tntmania/tnt-types.txt";

    private static List<TntType> all;
    private static Map<String, TntType> byId;

    private Catalog() {
    }

    public static synchronized List<TntType> all() {
        if (all == null) {
            try (InputStream in = Catalog.class.getResourceAsStream(FILE)) {
                if (in == null) throw new IllegalStateException("The TNT table " + FILE + " is missing");
                all = List.copyOf(parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))));
            } catch (IOException e) {
                throw new IllegalStateException("The TNT table could not be read", e);
            }
            Map<String, TntType> map = new HashMap<>();
            for (TntType t : all) map.put(t.id(), t);
            byId = map;
        }
        return all;
    }

    public static TntType get(String id) {
        all();
        return byId.get(id);
    }

    /** The groups in the order of the table, each with its types. */
    public static Map<String, List<TntType>> groups() {
        Map<String, List<TntType>> groups = new LinkedHashMap<>();
        for (TntType t : all()) groups.computeIfAbsent(t.group(), g -> new ArrayList<>()).add(t);
        return groups;
    }

    static List<TntType> parse(BufferedReader reader) throws IOException {
        List<TntType> list = new ArrayList<>();
        Set<String> ids = new HashSet<>(), labels = new HashSet<>(), recipes = new HashSet<>();
        String group = "TNT";
        String line;
        int number = 0;
        while ((line = reader.readLine()) != null) {
            number++;
            line = line.strip();
            if (line.startsWith("## ")) {
                group = line.substring(3).strip();
                continue;
            }
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\\|", -1);
            if (f.length != 11) throw bad(number, "has " + f.length + " parts, not 11");
            for (int i = 0; i < f.length; i++) f[i] = f[i].strip();
            String id = f[0];
            if (!id.matches("[a-z0-9_]+")) throw bad(number, "has a bad id: " + id);
            Kind kind;
            try {
                kind = Kind.valueOf(f[2]);
            } catch (IllegalArgumentException e) {
                throw bad(number, "has an unknown kind: " + f[2]);
            }
            try {
                double size = dash(f[3]) ? 0 : Double.parseDouble(f[3]);
                int count = dash(f[4]) ? 0 : Integer.parseInt(f[4]);
                String what = dash(f[5]) ? "" : f[5];
                int fuse = dash(f[6]) ? TntType.NORMAL_FUSE : Integer.parseInt(f[6]);
                int colour = Integer.parseInt(f[7], 16);
                String label = f[8];
                if (label.isEmpty() || label.length() > 4) throw bad(number, "needs a label of 1 to 4 letters");
                List<String> recipe = recipe(f[9]);
                if (recipe.size() < 2 || recipe.size() > 9) throw bad(number, "has a recipe of " + recipe.size() + " items");
                if (!ids.add(id)) throw bad(number, "uses the id " + id + " a second time");
                if (!labels.add(label)) throw bad(number, "uses the label " + label + " a second time");
                List<String> sorted = new ArrayList<>(recipe);
                sorted.sort(null);
                if (!recipes.add(sorted.toString())) throw bad(number, "has the same recipe as another TNT: " + sorted);
                if (fuse < 1) throw bad(number, "has a fuse below 1");
                list.add(new TntType(list.size(), group, id, f[1], kind, size, count, what, fuse, colour, label, recipe, f[10]));
            } catch (NumberFormatException e) {
                throw bad(number, "has a number that is not a number: " + e.getMessage());
            }
        }
        return list;
    }

    private static boolean dash(String s) {
        return s.isEmpty() || s.equals("-");
    }

    private static IllegalStateException bad(int line, String why) {
        return new IllegalStateException("tnt-types.txt line " + line + " " + why);
    }

    /** "x*3,@y" becomes three minecraft:x and one tntmania:y. One normal TNT is added unless it starts with "=". */
    static List<String> recipe(String text) {
        List<String> items = new ArrayList<>();
        boolean whole = text.startsWith("=");
        if (!whole) items.add("minecraft:tnt");
        for (String part : (whole ? text.substring(1) : text).split(",")) {
            part = part.strip();
            if (part.isEmpty()) continue;
            int times = 1;
            int star = part.indexOf('*');
            if (star >= 0) {
                times = Integer.parseInt(part.substring(star + 1));
                part = part.substring(0, star);
            }
            String item = part.startsWith("@") ? MOD + ":" + part.substring(1) : "minecraft:" + part;
            for (int i = 0; i < times; i++) items.add(item);
        }
        return List.copyOf(items);
    }

    /** How many letters fit on one line of the text under a TNT's name. */
    public static final int TOOLTIP_WIDTH = 40;

    /** The "what it does" text of a TNT, cut into short lines so it fits on a small screen. */
    public static List<String> aboutLines(TntType type) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : type.about().split(" ")) {
            if (word.isEmpty()) continue;
            if (!line.isEmpty() && line.length() + 1 + word.length() > TOOLTIP_WIDTH) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(line.isEmpty() ? "" : " ").append(word);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    /** Splits "a,b,c" and drops the spaces. */
    public static List<String> list(String text) {
        List<String> out = new ArrayList<>();
        for (String part : text.split(",")) {
            if (!part.isBlank()) out.add(part.strip());
        }
        return out;
    }
}
