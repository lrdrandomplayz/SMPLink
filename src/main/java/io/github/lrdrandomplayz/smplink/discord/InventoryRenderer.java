package io.github.lrdrandomplayz.smplink.discord;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import io.github.lrdrandomplayz.smplink.inventory.ItemView;

/**
 * Draws an inventory laid out like the vanilla inventory screen, with the ender chest as a
 * second section. Coordinates are vanilla GUI pixels; everything is drawn at {@link #U} times size.
 */
public final class InventoryRenderer {

    public record Contents(String name, List<ItemView> inventory, List<ItemView> armor, List<ItemView> enderChest) {
    }

    private static final int U = 3;
    private static final int SLOT = 18;
    private static final int WIDTH = 176;
    private static final int HEIGHT = 250;
    private static final int[] ARMOR_Y = {62, 44, 26, 8}; // armor slot 0 is boots, drawn at the bottom
    private static final int OFFHAND_SLOT = 40;
    private static final int[][] CRAFTING = {{98, 18}, {116, 18}, {98, 36}, {116, 36}};
    private static final int MAIN_Y = 84;
    private static final int HOTBAR_Y = 142;
    private static final int ENDER_ICON_Y = 168;
    private static final int ENDER_Y = 188;

    private static final Color PANEL = new Color(0xC6C6C6);
    private static final Color HIGHLIGHT = Color.WHITE;
    private static final Color SHADE = new Color(0x555555);
    private static final Color SLOT_FILL = new Color(0x8B8B8B);
    private static final Color SLOT_DARK = new Color(0x373737);
    private static final Color TEXT_SHADOW = new Color(0x3F3F3F);
    private static final Color GLINT = new Color(128, 64, 255, 72);
    private static final Color MISSING = new Color(0xF800F8);

    // 5x7 digits in the style of the Minecraft font, so no font has to be installed on the host.
    private static final String[][] DIGITS = {
            {"01110", "10001", "10011", "10101", "11001", "10001", "01110"},
            {"00100", "01100", "00100", "00100", "00100", "00100", "11111"},
            {"01110", "10001", "00001", "00110", "01000", "10000", "11111"},
            {"01110", "10001", "00001", "00110", "00001", "10001", "01110"},
            {"00011", "00101", "01001", "10001", "11111", "00001", "00001"},
            {"11111", "10000", "11110", "00001", "00001", "10001", "01110"},
            {"00110", "01000", "10000", "11110", "10001", "10001", "01110"},
            {"11111", "10001", "00001", "00010", "00100", "00100", "00100"},
            {"01110", "10001", "10001", "01110", "10001", "10001", "01110"},
            {"01110", "10001", "10001", "01111", "00001", "00010", "01100"},
    };

    // Item IDs ("minecraft:diamond_sword") from the server's own item list; icons are read from iconFolder.
    private final java.util.Set<String> itemIds;
    private final Map<String, Optional<BufferedImage>> icons = new ConcurrentHashMap<>();
    private final HttpClient http;
    private volatile java.nio.file.Path iconFolder;

    /** {@code itemIds}: every item a trade may name, without the "minecraft:" prefix (see {@link #serverItemIds()}). */
    public InventoryRenderer(HttpClient http, java.util.Collection<String> itemIds) {
        this.http = http;
        java.util.Set<String> ids = new java.util.TreeSet<>();
        itemIds.forEach(id -> ids.add("minecraft:" + id.toLowerCase(Locale.ROOT)));
        this.itemIds = java.util.Collections.unmodifiableSet(ids);
    }

    /** The running server's items. Needs a running server (Bukkit's registries), so not for unit tests. */
    public static List<String> serverItemIds() {
        List<String> ids = new java.util.ArrayList<>();
        for (org.bukkit.Material material : org.bukkit.Material.values()) {
            if (!material.isLegacy() && material.isItem() && !material.isAir()) {
                ids.add(material.name().toLowerCase(Locale.ROOT));
            }
        }
        return ids;
    }

    /**
     * Where item icons are read from: {@code <item_id>.png}, e.g. diamond_sword.png. Minecraft's textures can't be
     * shipped in this open-source jar, so the admin supplies them (see the README); items without an icon are drawn
     * as the missing-texture square.
     */
    public void setIconFolder(java.nio.file.Path folder) {
        this.iconFolder = folder;
        icons.clear();
    }

    public byte[] render(Contents inv) throws IOException {
        BufferedImage skin = skin(inv.name());
        BufferedImage image = new BufferedImage(WIDTH * U, HEIGHT * U, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            drawPanel(g);

            Map<Integer, ItemView> main = bySlot(inv.inventory());
            Map<Integer, ItemView> armor = bySlot(inv.armor());
            Map<Integer, ItemView> ender = bySlot(inv.enderChest());

            for (int slot = 0; slot < ARMOR_Y.length; slot++) {
                place(g, 8, ARMOR_Y[slot], armor.get(slot));
            }
            place(g, 77, 62, main.get(OFFHAND_SLOT));
            for (int[] xy : CRAFTING) {
                place(g, xy[0], xy[1], null);
            }
            place(g, 154, 28, null);
            drawArrow(g);
            for (int i = 0; i < 27; i++) {
                place(g, 8 + (i % 9) * SLOT, MAIN_Y + (i / 9) * SLOT, main.get(9 + i));
            }
            for (int i = 0; i < 9; i++) {
                place(g, 8 + i * SLOT, HOTBAR_Y, main.get(i));
            }
            for (int i = 0; i < 27; i++) {
                place(g, 8 + (i % 9) * SLOT, ENDER_Y + (i / 9) * SLOT, ender.get(i));
            }
            icon("ENDER_CHEST").ifPresent(icon -> g.drawImage(icon, 8 * U, ENDER_ICON_Y * U, 16 * U, 16 * U, null));

            // Player preview: a sunken black box with the skin fitted inside.
            rect(g, 25, 7, 50, 71, SLOT_DARK);
            rect(g, 26, 8, 49, 70, HIGHLIGHT);
            rect(g, 26, 8, 48, 69, Color.BLACK);
            if (skin != null) {
                double scale = Math.min((43.0 * U) / skin.getWidth(), (64.0 * U) / skin.getHeight());
                int w = (int) (skin.getWidth() * scale);
                int h = (int) (skin.getHeight() * scale);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(skin, 26 * U + (49 * U - w) / 2, 8 * U + (70 * U - h) / 2, w, h, null);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            }
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** Every item ID with an icon, without the "minecraft:" prefix, e.g. "diamond_sword". */
    public List<String> itemIds() {
        return itemIds.stream()
                .map(key -> key.substring(key.indexOf(':') + 1))
                .sorted()
                .toList();
    }

    public boolean hasItem(String id) {
        return itemIds.contains("minecraft:" + id.toLowerCase(Locale.ROOT));
    }

    /**
     * Draws a trade the way the villager trading screen does: up to two offered stacks, an arrow and
     * the wanted stack in a large result slot. Missing stacks leave their slot empty.
     */
    public byte[] renderTrade(ItemView first, ItemView second, ItemView result) throws IOException {
        return renderTrade(first, second, result, null);
    }

    /** As above, with a second large result slot when the trader wants two things. */
    public byte[] renderTrade(ItemView first, ItemView second, ItemView result, ItemView result2) throws IOException {
        // Without a second stack its slot is left out and everything after it moves left.
        int shift = second == null ? 22 : 0;
        int width = 116 - shift + (result2 == null ? 0 : 30);
        int height = 42;
        BufferedImage image = new BufferedImage(width * U, height * U, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            rect(g, 0, 0, width, height, Color.BLACK);
            rect(g, 1, 1, width - 2, height - 2, HIGHLIGHT);
            rect(g, 3, 3, width - 4, height - 4, SHADE);
            rect(g, 3, 3, width - 6, height - 6, PANEL);
            place(g, 9, 13, first);
            if (second != null) {
                place(g, 31, 13, second);
            }
            int arrow = 57 - shift;
            rect(g, arrow, 20, 10, 3, SLOT_FILL);
            for (int i = 0; i < 5; i++) {
                rect(g, arrow + 10 + i, 17 + i, 1, 9 - 2 * i, SLOT_FILL);
            }
            // The result slot is the large one, as in vanilla.
            int resultX = 81 - shift;
            rect(g, resultX, 8, 26, 26, SLOT_DARK);
            rect(g, resultX + 1, 9, 25, 25, HIGHLIGHT);
            rect(g, resultX + 1, 9, 24, 24, SLOT_FILL);
            if (result != null) {
                drawItem(g, result, resultX + 5, 13);
            }
            if (result2 != null) {
                int secondX = resultX + 30;
                rect(g, secondX, 8, 26, 26, SLOT_DARK);
                rect(g, secondX + 1, 9, 25, 25, HIGHLIGHT);
                rect(g, secondX + 1, 9, 24, 24, SLOT_FILL);
                drawItem(g, result2, secondX + 5, 13);
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private void place(Graphics2D g, int x, int y, ItemView item) {
        rect(g, x - 1, y - 1, SLOT, SLOT, SLOT_DARK);
        rect(g, x, y, SLOT - 1, SLOT - 1, HIGHLIGHT);
        rect(g, x, y, SLOT - 2, SLOT - 2, SLOT_FILL);
        if (item != null) {
            drawItem(g, item, x, y);
        }
    }

    private void drawItem(Graphics2D g, ItemView item, int x, int y) {
        Optional<BufferedImage> icon = icon(item.material());
        if (icon.isPresent()) {
            if (item.enchants() != null && !item.enchants().isEmpty()) {
                // Tint only the icon's own pixels so the glint doesn't spill onto the slot.
                BufferedImage layer = new BufferedImage(16 * U, 16 * U, BufferedImage.TYPE_INT_ARGB);
                Graphics2D lg = layer.createGraphics();
                lg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                lg.drawImage(icon.get(), 0, 0, 16 * U, 16 * U, null);
                lg.setComposite(AlphaComposite.SrcAtop);
                lg.setColor(GLINT);
                lg.fillRect(0, 0, 16 * U, 16 * U);
                lg.dispose();
                g.drawImage(layer, x * U, y * U, null);
            } else {
                g.drawImage(icon.get(), x * U, y * U, 16 * U, 16 * U, null);
            }
        } else {
            // Vanilla's missing-texture checkerboard, so unknown items are still visible.
            rect(g, x, y, 8, 8, MISSING);
            rect(g, x + 8, y + 8, 8, 8, MISSING);
            rect(g, x + 8, y, 8, 8, Color.BLACK);
            rect(g, x, y + 8, 8, 8, Color.BLACK);
        }

        if (item.maxDamage() > 0 && item.damage() > 0) {
            double left = Math.max(0, 1 - (double) item.damage() / item.maxDamage());
            rect(g, x + 2, y + 13, 13, 2, Color.BLACK);
            g.setColor(Color.getHSBColor((float) (left / 3.0), 1f, 1f));
            g.fillRect((x + 2) * U, (y + 13) * U, (int) Math.round(13 * left) * U, U);
        }

        if (item.amount() > 1) {
            drawNumber(g, item.amount(), x + 17, y + 10, TEXT_SHADOW);
            drawNumber(g, item.amount(), x + 16, y + 9, Color.WHITE);
        }
    }

    private Optional<BufferedImage> icon(String material) {
        String id = "minecraft:" + material.toLowerCase(Locale.ROOT);
        java.nio.file.Path folder = iconFolder;
        if (folder == null) {
            return Optional.empty();
        }
        return icons.computeIfAbsent(id, key -> {
            java.nio.file.Path file = folder.resolve(key.substring(key.indexOf(':') + 1) + ".png");
            if (!java.nio.file.Files.isRegularFile(file)) {
                return Optional.empty();
            }
            try (InputStream in = java.nio.file.Files.newInputStream(file)) {
                return Optional.ofNullable(ImageIO.read(in));
            } catch (IOException e) {
                return Optional.empty();
            }
        });
    }

    /** Skin render by name from mc-heads.net; decoration only, so any failure just leaves the box black. */
    private BufferedImage skin(String name) {
        if (name == null || http == null) {
            return null;
        }
        try {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(
                            URI.create("https://mc-heads.net/body/" + URLEncoder.encode(name, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(6)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            return response.statusCode() == 200 ? ImageIO.read(new ByteArrayInputStream(response.body())) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void drawPanel(Graphics2D g) {
        rect(g, 0, 0, WIDTH, HEIGHT, Color.BLACK);
        rect(g, 1, 1, WIDTH - 2, HEIGHT - 2, HIGHLIGHT);
        rect(g, 3, 3, WIDTH - 4, HEIGHT - 4, SHADE);
        rect(g, 3, 3, WIDTH - 6, HEIGHT - 6, PANEL);
        // Divider between the player inventory and the ender chest.
        rect(g, 7, 164, WIDTH - 14, 1, SHADE);
        rect(g, 7, 165, WIDTH - 14, 1, HIGHLIGHT);
    }

    private static void drawArrow(Graphics2D g) {
        rect(g, 135, 34, 10, 3, SLOT_FILL);
        for (int i = 0; i < 5; i++) {
            rect(g, 145 + i, 31 + i, 1, 9 - 2 * i, SLOT_FILL);
        }
    }

    private static void drawNumber(Graphics2D g, int value, int rightX, int topY, Color color) {
        String text = Integer.toString(value);
        int x = rightX - text.length() * 6 + 1;
        for (char c : text.toCharArray()) {
            String[] rows = DIGITS[c - '0'];
            for (int dy = 0; dy < rows.length; dy++) {
                for (int dx = 0; dx < 5; dx++) {
                    if (rows[dy].charAt(dx) == '1') {
                        rect(g, x + dx, topY + dy, 1, 1, color);
                    }
                }
            }
            x += 6;
        }
    }

    private static void rect(Graphics2D g, int x, int y, int w, int h, Color color) {
        g.setColor(color);
        g.fillRect(x * U, y * U, w * U, h * U);
    }

    private static Map<Integer, ItemView> bySlot(List<ItemView> items) {
        return items.stream().collect(Collectors.toMap(ItemView::slot, Function.identity(), (a, b) -> a));
    }
}
