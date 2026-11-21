package io.github.lrdrandomplayz.smplink.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import io.github.lrdrandomplayz.smplink.inventory.ItemView;
import org.junit.jupiter.api.Test;

class InventoryRendererTest {

    private static final int U = 3;

    @Test
    void drawsItemsIntoTheirSlots() throws Exception {
        // A null HttpClient skips the skin download, so the test runs offline.
        InventoryRenderer renderer = new InventoryRenderer(null, java.util.List.of("netherite_sword", "oak_log", "shield", "diamond_helmet", "diamond", "elytra", "enchanted_book", "totem_of_undying"));
        List<ItemView> inventory = List.of(
                new ItemView(0, "NETHERITE_SWORD", 1, "Order", Map.of("sharpness", 5), 400, 2031),
                new ItemView(9, "OAK_LOG", 64, null, Map.of(), 0, 0),
                new ItemView(40, "SHIELD", 1, null, Map.of(), 0, 336),
                new ItemView(20, "NOT_A_REAL_ITEM", 3, null, Map.of(), 0, 0));
        List<ItemView> armor = List.of(new ItemView(3, "DIAMOND_HELMET", 1, null, Map.of("protection", 4), 0, 363));
        List<ItemView> ender = List.of(new ItemView(26, "DIAMOND", 64, null, Map.of(), 0, 0));

        byte[] png = renderer.render(new InventoryRenderer.Contents("Tester", inventory, armor, ender));
        Path out = Path.of("build", "test-output", "inventory.png");
        Files.createDirectories(out.getParent());
        Files.write(out, png);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(176 * U, image.getWidth());
        assertEquals(250 * U, image.getHeight());

        int emptySlot = 0xFF8B8B8B;
        // Centre of hotbar slot 0 (x 8, y 142) holds the sword, not the empty slot colour.
        assertNotEquals(emptySlot, image.getRGB((8 + 8) * U, (142 + 8) * U));
        // Hotbar slot 1 is empty.
        assertEquals(emptySlot, image.getRGB((26 + 1) * U, (142 + 1) * U));
        // The unknown item shows the magenta missing-texture square in main slot 20 (row 1, column 2).
        assertEquals(0xFFF800F8, image.getRGB((8 + 2 * 18 + 2) * U, (84 + 18 + 2) * U));
        // The damaged sword draws a durability bar background at its bottom edge.
        assertEquals(0xFF000000, image.getRGB((8 + 14) * U, (142 + 14) * U));
    }
}
