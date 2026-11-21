package io.github.lrdrandomplayz.smplink.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Draws the bot's emoji set ({@link Glyph}): Minecraft item pictures (the same 32×32 renders /inv uses) and
 * hand-made 16×16 pixel icons with a Minecraft-style bevel, all scaled up to 128×128. Animated glyphs get the
 * enchanted-item shimmer.
 */
final class GlyphArt {

    static final int SIZE = 128;
    private static final int FRAMES = 16;
    private static final int FRAME_DELAY = 6; // hundredths of a second

    private GlyphArt() {
    }

    /** The picture: PNG bytes, or GIF bytes when {@link Glyph#animated()}. */
    static byte[] draw(Glyph glyph) throws IOException {
        int[][] grid = glyph.item() != null ? item(glyph.item()) : custom(glyph);
        if (!glyph.animated()) {
            return png(grid);
        }
        List<int[][]> frames = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();
        for (int f = 0; f < FRAMES; f++) {
            frames.add(glint(grid, f));
            delays.add(f == 0 ? FRAME_DELAY * 4 : FRAME_DELAY); // a short rest between sweeps
        }
        quantize(frames);
        return Gif.gif(frames, delays, SIZE / grid.length);
    }

    // --- Minecraft items ------------------------------------------------------------------------------------

    /**
     * plugins/SMPLink/icons, the same item pictures /inv uses (diamond_sword.png). Minecraft's textures can't ship in
     * the jar, so item glyphs only get their picture when the admin has added icons; otherwise they stay normal emojis.
     */
    static void setIconFolder(java.nio.file.Path folder) {
        iconFolder = folder;
    }

    private static volatile java.nio.file.Path iconFolder;

    private static int[][] item(String id) throws IOException {
        java.nio.file.Path folder = iconFolder;
        String name = id.substring(id.indexOf(':') + 1);
        java.nio.file.Path file = folder == null ? null : folder.resolve(name + ".png");
        if (file == null || !java.nio.file.Files.isRegularFile(file)) {
            throw new java.io.FileNotFoundException(name + ".png");
        }
        BufferedImage image;
        try (InputStream in = java.nio.file.Files.newInputStream(file)) {
            image = ImageIO.read(in);
        }
        if (image == null) {
            throw new IOException(file + " isn't a picture");
        }
        int[][] grid = new int[image.getHeight()][image.getWidth()];
        for (int y = 0; y < grid.length; y++) {
            for (int x = 0; x < grid[0].length; x++) {
                int argb = image.getRGB(x, y);
                // GIF transparency is all or nothing, so soft edges are rounded either way.
                grid[y][x] = (argb >>> 24) < 128 ? 0 : 0xFF000000 | (argb & 0xFFFFFF);
            }
        }
        return grid;
    }

    // --- Hand-made icons ------------------------------------------------------------------------------------

    private static final String[] QUESTION = {
            "................",
            "....XXXXXXX.....",
            "...XXXXXXXXX....",
            "...XXX...XXX....",
            "...XXX...XXX....",
            ".........XXX....",
            "........XXX.....",
            ".......XXX......",
            "......XXX.......",
            "......XXX.......",
            "................",
            "......XXX.......",
            "......XXX.......",
            "......XXX.......",
            "................",
            "................"};

    private static int[][] custom(Glyph glyph) {
        BufferedImage mask = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = mask.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(Color.WHITE);
        // Marks drawn in black inside the shape (the "!" on the warning sign) become outline-coloured holes.
        Set<Long> marks = new HashSet<>();
        switch (glyph.shape()) {
            case "check" -> {
                g.setStroke(new BasicStroke(3, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
                g.drawPolyline(new int[]{3, 6, 12}, new int[]{8, 11, 4}, 3);
            }
            case "cross" -> {
                g.setStroke(new BasicStroke(3, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
                g.drawLine(4, 4, 11, 11);
                g.drawLine(11, 4, 4, 11);
            }
            case "warn" -> {
                g.fillPolygon(new Polygon(new int[]{7, 8, 15, 15, 0, 0}, new int[]{1, 1, 14, 15, 15, 14}, 6));
                for (int y = 5; y <= 10; y++) {
                    marks.add(key(7, y));
                    marks.add(key(8, y));
                }
                marks.add(key(7, 12));
                marks.add(key(8, 12));
                marks.add(key(7, 13));
                marks.add(key(8, 13));
            }
            case "alert" -> {
                g.fillRect(6, 1, 4, 9);
                g.fillRect(6, 12, 4, 3);
            }
            case "question" -> {
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        if (QUESTION[y].charAt(x) == 'X') {
                            g.fillRect(x, y, 1, 1);
                        }
                    }
                }
            }
            case "plus" -> {
                g.fillRect(6, 2, 4, 12);
                g.fillRect(2, 6, 12, 4);
            }
            case "minus" -> g.fillRect(2, 6, 12, 4);
            case "arrow" -> {
                g.fillRect(1, 6, 8, 4);
                g.fillPolygon(new Polygon(new int[]{8, 15, 8}, new int[]{1, 8, 15}, 3));
            }
            case "up" -> g.fillPolygon(new Polygon(new int[]{1, 8, 15}, new int[]{12, 3, 12}, 3));
            case "down" -> g.fillPolygon(new Polygon(new int[]{1, 8, 15}, new int[]{4, 13, 4}, 3));
            default -> g.fillOval(2, 2, 12, 12); // "dot"
        }
        g.dispose();
        boolean[][] in = new boolean[16][16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                in[y][x] = (mask.getRGB(x, y) >>> 24) > 0 && !marks.contains(key(x, y));
            }
        }
        return bevel(in, glyph.colour(), marks);
    }

    private static long key(int x, int y) {
        return (long) y << 8 | x;
    }

    /** Fills the shape in {@code colour} with a lit top-left edge, a shaded bottom-right edge and a dark outline. */
    private static int[][] bevel(boolean[][] in, int colour, Set<Long> marks) {
        int outline = 0xFF000000 | mix(colour, 0x000000, 0.75);
        int light = 0xFF000000 | mix(colour, 0xFFFFFF, 0.45);
        int shade = 0xFF000000 | mix(colour, 0x000000, 0.35);
        int main = 0xFF000000 | colour;
        int[][] grid = new int[16][16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                if (in[y][x]) {
                    boolean top = !at(in, x, y - 1) || !at(in, x - 1, y);
                    boolean bottom = !at(in, x, y + 1) || !at(in, x + 1, y);
                    grid[y][x] = top && !bottom ? light : bottom && !top ? shade : main;
                } else if (marks.contains(key(x, y)) || at(in, x - 1, y) || at(in, x + 1, y) || at(in, x, y - 1) || at(in, x, y + 1)) {
                    grid[y][x] = outline;
                }
            }
        }
        return grid;
    }

    private static boolean at(boolean[][] in, int x, int y) {
        return y >= 0 && y < in.length && x >= 0 && x < in[0].length && in[y][x];
    }

    // --- Animation ------------------------------------------------------------------------------------------

    /** The enchanted-item shimmer: a violet band sweeping diagonally across the opaque pixels. */
    private static int[][] glint(int[][] grid, int frame) {
        int n = grid.length;
        double centre = -n * 0.6 + frame * (n * 2.2 / FRAMES);
        int[][] out = new int[n][n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int argb = grid[y][x];
                if (argb == 0) {
                    continue;
                }
                double d = Math.abs((x + y * 0.6) - centre) / (n * 0.18);
                double t = d >= 1 ? 0 : (1 - d) * 0.65;
                // A few fixed strengths keep the colour count down.
                t = Math.round(t * 4) / 4.0;
                out[y][x] = t == 0 ? argb : 0xFF000000 | mix(argb, 0xD7A6FF, t);
            }
        }
        return out;
    }

    /** Drops low colour bits until the frames share at most 255 colours (GIF's limit, plus transparency). */
    private static void quantize(List<int[][]> frames) {
        for (int bits = 0; bits <= 5; bits++) {
            int mask = (0xFF << bits) & 0xFF;
            int full = 0xFF000000 | mask << 16 | mask << 8 | mask;
            Set<Integer> colours = new HashSet<>();
            for (int[][] frame : frames) {
                for (int[] row : frame) {
                    for (int argb : row) {
                        if (argb != 0) {
                            colours.add(argb & full);
                        }
                    }
                }
            }
            if (colours.size() <= 255) {
                for (int[][] frame : frames) {
                    for (int[] row : frame) {
                        for (int x = 0; x < row.length; x++) {
                            if (row[x] != 0) {
                                row[x] &= full;
                            }
                        }
                    }
                }
                return;
            }
        }
    }

    // --- Output ---------------------------------------------------------------------------------------------

    private static byte[] png(int[][] grid) throws IOException {
        int scale = SIZE / grid.length;
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                image.setRGB(x, y, grid[y / scale][x / scale]);
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return r << 16 | g << 8 | bl;
    }
}
