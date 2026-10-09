package net.husein.tntmania.gen;

import net.husein.tntmania.catalog.Catalog;
import net.husein.tntmania.catalog.TntType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes everything the game needs for each TNT in the table: the three pictures of the block, its model files,
 * its drop, its recipe and its texts. Also writes the list of all TNTs (ALL-250-TNT.html) for people to read.
 * Runs as part of the build: gradle genAssets.
 */
public final class Gen {
    private static final String MOD = Catalog.MOD;
    private static final int SIZE = 16;

    /** A tiny 3x5 alphabet for the label on the block. Each letter is five rows of three dots. */
    private static final Map<Character, String[]> FONT = new LinkedHashMap<>();

    static {
        String[] rows = {
                "A:010,101,111,101,101", "B:110,101,110,101,110", "C:011,100,100,100,011", "D:110,101,101,101,110",
                "E:111,100,110,100,111", "F:111,100,110,100,100", "G:011,100,101,101,011", "H:101,101,111,101,101",
                "I:111,010,010,010,111", "J:001,001,001,101,010", "K:101,101,110,101,101", "L:100,100,100,100,111",
                "M:101,111,101,101,101", "N:110,101,101,101,101", "O:010,101,101,101,010", "P:110,101,110,100,100",
                "Q:010,101,101,110,011", "R:110,101,110,101,101", "S:011,100,010,001,110", "T:111,010,010,010,010",
                "U:101,101,101,101,111", "V:101,101,101,101,010", "W:101,101,101,111,101", "X:101,101,010,101,101",
                "Y:101,101,010,010,010", "Z:111,001,010,100,111", "0:111,101,101,101,111", "1:010,110,010,010,111",
                "2:110,001,010,100,111", "3:110,001,010,001,110", "4:101,101,111,001,001", "5:111,100,110,001,110",
                "6:011,100,110,101,010", "7:111,001,010,010,010", "8:010,101,010,101,010", "9:010,101,011,001,110",
                "+:000,010,111,010,000", "-:000,000,111,000,000", "!:010,010,010,000,010", "?:110,001,010,000,010"
        };
        for (String row : rows) FONT.put(row.charAt(0), row.substring(2).split(","));
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[0]);
        Path project = Path.of(args[1]);
        List<TntType> all = Catalog.all();
        if (all.size() != Catalog.EXPECTED) {
            throw new IllegalStateException("The table has " + all.size() + " TNT types, but there should be " + Catalog.EXPECTED);
        }
        for (TntType t : all) {
            for (char c : t.label().toCharArray()) {
                if (!FONT.containsKey(c)) throw new IllegalStateException(t.id() + ": the label letter '" + c + "' cannot be drawn");
            }
        }
        Path assets = out.resolve("assets").resolve(MOD);
        Path data = out.resolve("data").resolve(MOD);
        Map<String, String> lang = new LinkedHashMap<>();
        lang.put("itemGroup." + MOD, "TNT Mania: 250 TNT");
        lang.put("key." + MOD + ".settings", "TNT Mania: open the settings");
        lang.put("key." + MOD + ".fill", "TNT Mania: fill mode on/off");
        Map<String, byte[]> sides = new LinkedHashMap<>();

        for (TntType t : all) {
            String id = t.id();
            byte[] side = png(side(t.colour(), t.label(), id));
            sides.put(id, side);
            write(assets.resolve("textures/block/" + id + "_side.png"), side);
            write(assets.resolve("textures/block/" + id + "_top.png"), png(cap(t.colour(), id, true)));
            write(assets.resolve("textures/block/" + id + "_bottom.png"), png(cap(t.colour(), id, false)));

            String model = MOD + ":block/" + id;
            text(assets.resolve("blockstates/" + id + ".json"), "{\"variants\":{\"\":{\"model\":\"" + model + "\"}}}");
            text(assets.resolve("models/block/" + id + ".json"), "{\"parent\":\"minecraft:block/cube_bottom_top\",\"textures\":{"
                    + "\"bottom\":\"" + model + "_bottom\",\"side\":\"" + model + "_side\",\"top\":\"" + model + "_top\"}}");
            text(assets.resolve("items/" + id + ".json"), "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + model + "\"}}");

            text(data.resolve("loot_table/blocks/" + id + ".json"), "{\"type\":\"minecraft:block\",\"pools\":[{\"rolls\":1.0,"
                    + "\"conditions\":[{\"condition\":\"minecraft:survives_explosion\"}],"
                    + "\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"" + MOD + ":" + id + "\"}]}]}");

            StringBuilder items = new StringBuilder();
            for (String item : t.recipe()) items.append(items.isEmpty() ? "" : ",").append('"').append(item).append('"');
            text(data.resolve("recipe/" + id + ".json"), "{\"type\":\"minecraft:crafting_shapeless\",\"category\":\"redstone\","
                    + "\"ingredients\":[" + items + "],\"result\":{\"id\":\"" + MOD + ":" + id + "\",\"count\":1}}");

            lang.put("block." + MOD + "." + id, t.name());
            List<String> about = Catalog.aboutLines(t);
            for (int line = 0; line < about.size(); line++) lang.put(MOD + ".about." + id + "." + line, about.get(line));
        }

        StringBuilder json = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, String> e : lang.entrySet()) {
            json.append("  \"").append(e.getKey()).append("\": \"").append(escape(e.getValue())).append('"').append(++i < lang.size() ? ",\n" : "\n");
        }
        text(assets.resolve("lang/en_us.json"), json.append("}\n").toString());

        write(assets.resolve("icon.png"), png(scale(side(0xD23B2F, "250", "icon"), 8)));
        write(project.resolve("ALL-250-TNT.png"), png(sheet(all)));
        text(project.resolve("ALL-250-TNT.html"), page(sides));
        System.out.println("Made " + all.size() + " TNT types: " + (all.size() * 3) + " pictures, " + (all.size() * 5) + " game files, and the list.");
    }

    // ---- pictures -------------------------------------------------------------------------------------------

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int shade(int rgb, int by) {
        return 0xFF000000 | clamp((rgb >> 16 & 255) + by) << 16 | clamp((rgb >> 8 & 255) + by) << 8 | clamp((rgb & 255) + by);
    }

    private static int mix(int a, int b, double t) {
        int r = (int) Math.round((a >> 16 & 255) * (1 - t) + (b >> 16 & 255) * t);
        int g = (int) Math.round((a >> 8 & 255) * (1 - t) + (b >> 8 & 255) * t);
        int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    private static int brightness(int rgb) {
        return ((rgb >> 16 & 255) * 3 + (rgb >> 8 & 255) * 6 + (rgb & 255)) / 10;
    }

    /** A small, always-the-same wobble per dot, so the blocks do not look flat. */
    private static int grain(String id, int x, int y) {
        int h = id.hashCode() * 31 + x * 73856093 + y * 19349663;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return (h & 7) - 3;
    }

    /** The side: sticks of dynamite in the block's colour, with a paper band and the label on it. */
    static BufferedImage side(int colour, String label, String id) {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        boolean light = brightness(colour) > 185;
        int paper = light ? 0xFF3A3A42 : 0xFFF1ECDF;
        int paperEdge = light ? 0xFF24242A : 0xFFCBC3AF;
        int ink = light ? 0xFFF6F6F6 : mix(colour, 0x000000, 0.72);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int c;
                if (y >= 5 && y <= 11) {
                    c = y == 5 || y == 11 ? paperEdge : paper;
                    c = shade(c, grain(id, x, y) / 2);
                } else {
                    int by = switch (x % 4) {
                        case 0 -> 16;
                        case 3 -> -30;
                        default -> 0;
                    };
                    if (y == 0) by -= 18;
                    if (y == 15) by -= 34;
                    if (y == 4 || y == 12) by -= 14;
                    c = shade(colour, by + grain(id, x, y));
                }
                img.setRGB(x, y, c);
            }
        }
        int width = label.length() * 3 + label.length() - 1;
        int left = (SIZE - width + 1) / 2;
        for (int i = 0; i < label.length(); i++) {
            String[] glyph = FONT.get(label.charAt(i));
            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 3; col++) {
                    if (glyph[row].charAt(col) == '1') img.setRGB(left + i * 4 + col, 6 + row, ink);
                }
            }
        }
        return img;
    }

    /** The top (with the fuse) or the bottom: the round ends of the sticks. */
    static BufferedImage cap(int colour, String id, boolean top) {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        int base = top ? 0 : -24;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                boolean edge = x == 0 || y == 0 || x == 15 || y == 15;
                int by = base + (edge ? -30 : 0);
                int cx = (x - 1) % 4, cy = (y - 1) % 4;
                if (!edge && x < 15 && y < 15) {
                    if (cx == 3 || cy == 3) by -= 20;
                    else if (cx == 0 && cy == 0) by += 20;
                }
                img.setRGB(x, y, shade(colour, by + grain(id, x, y + 20)));
            }
        }
        if (top) {
            for (int y = 6; y <= 9; y++) {
                for (int x = 6; x <= 9; x++) {
                    boolean core = x >= 7 && x <= 8 && y >= 7 && y <= 8;
                    img.setRGB(x, y, core ? 0xFFE9E0C6 : 0xFF2B2B2B);
                }
            }
        }
        return img;
    }

    private static BufferedImage scale(BufferedImage src, int by) {
        BufferedImage img = new BufferedImage(src.getWidth() * by, src.getHeight() * by, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) img.setRGB(x, y, src.getRGB(x / by, y / by));
        }
        return img;
    }

    /** One picture with all the blocks next to each other, 25 in a row. */
    private static BufferedImage sheet(List<TntType> all) {
        int perRow = 25, cell = 68, pad = 4;
        int rows = (all.size() + perRow - 1) / perRow;
        BufferedImage img = new BufferedImage(perRow * cell + pad, rows * cell + pad, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) img.setRGB(x, y, 0xFF20222A);
        }
        for (TntType t : all) {
            BufferedImage big = scale(side(t.colour(), t.label(), t.id()), 4);
            int ox = pad + t.index() % perRow * cell, oy = pad + t.index() / perRow * cell;
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) img.setRGB(ox + x, oy + y, big.getRGB(x, y));
            }
        }
        return img;
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bytes);
        return bytes.toByteArray();
    }

    // ---- the list for people ----------------------------------------------------------------------------------

    private static String pretty(String item) {
        String path = item.substring(item.indexOf(':') + 1);
        if (item.startsWith(MOD + ":")) {
            TntType t = Catalog.get(path);
            return t == null ? path : t.name();
        }
        StringBuilder out = new StringBuilder();
        for (String word : path.split("_")) {
            if (word.isEmpty()) continue;
            out.append(out.isEmpty() ? "" : " ").append(word.equals("tnt") ? "TNT" : Character.toUpperCase(word.charAt(0)) + word.substring(1));
        }
        return out.toString();
    }

    /** "TNT + 2 x Oak Door" */
    private static String recipeText(TntType t) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String item : t.recipe()) counts.merge(pretty(item), 1, Integer::sum);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) parts.add((e.getValue() > 1 ? e.getValue() + " x " : "") + e.getKey());
        return String.join(" + ", parts);
    }

    private static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String page(Map<String, byte[]> sides) {
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<title>TNT Mania: all 250 TNT</title>\n<style>\n")
                .append("body{font-family:Segoe UI,Arial,sans-serif;background:#1c1e26;color:#e8e8ec;margin:0;padding:24px;max-width:1100px;margin:auto}\n")
                .append("h1{margin:0 0 4px}h2{margin:34px 0 8px;border-bottom:2px solid #d23b2f;padding-bottom:4px}\n")
                .append("p.top{color:#b8bac4;margin:4px 0}table{border-collapse:collapse;width:100%}\n")
                .append("td{padding:6px 10px;border-bottom:1px solid #30333f;vertical-align:middle}\n")
                .append("td.pic{width:52px}td.name{width:210px;font-weight:600}td.how{color:#b8bac4;width:300px;font-size:14px}\n")
                .append("img{width:48px;height:48px;image-rendering:pixelated;display:block}\n")
                .append("</style></head><body>\n<h1>TNT Mania: all 250 TNT</h1>\n")
                .append("<p class=\"top\">Craft each one from the items in the last column, in any order, in a crafting table. ")
                .append("Light it with flint and steel, a redstone signal, fire, or another blast, like normal TNT.</p>\n")
                .append("<p class=\"top\">\"Forward\" always means the way you were looking when you placed the block.</p>\n");
        for (Map.Entry<String, List<TntType>> group : Catalog.groups().entrySet()) {
            b.append("<h2>").append(html(group.getKey())).append(" (").append(group.getValue().size()).append(")</h2>\n<table>\n");
            for (TntType t : group.getValue()) {
                b.append("<tr><td class=\"pic\"><img alt=\"\" src=\"data:image/png;base64,")
                        .append(Base64.getEncoder().encodeToString(sides.get(t.id()))).append("\"></td><td class=\"name\">")
                        .append(html(t.name())).append("</td><td>").append(html(t.about())).append("</td><td class=\"how\">")
                        .append(html(recipeText(t))).append("</td></tr>\n");
            }
            b.append("</table>\n");
        }
        return b.append("</body></html>\n").toString();
    }

    // ---- files ------------------------------------------------------------------------------------------------

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private static void text(Path file, String content) throws IOException {
        write(file, content.getBytes(StandardCharsets.UTF_8));
    }
}
