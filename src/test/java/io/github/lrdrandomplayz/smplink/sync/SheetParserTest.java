package io.github.lrdrandomplayz.smplink.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SheetParserTest {

    private static List<List<JsonElement>> sheet(String json) {
        List<List<JsonElement>> rows = new ArrayList<>();
        for (JsonElement row : JsonParser.parseString(json).getAsJsonArray()) {
            List<JsonElement> cells = new ArrayList<>();
            ((JsonArray) row).forEach(cells::add);
            rows.add(cells);
        }
        return rows;
    }

    @Test
    void readsIdsUsernamesAndStackedTables() {
        SheetParser.Result result = SheetParser.parse(sheet("""
                [["MC Username", "Name", "IG", "Discord"],
                 ["Steve", "kayden", "", "100000000000000099"],
                 ["satoku233", "", "", "@W0ving#0"],
                 ["cvsu", "", "", "-"],
                 ["im_lz", "", "", "lz the dev!"],
                 ["Numbered", "", "", 100000000000000099],
                 [],
                 ["MC Username", "", "", "Minecraft Version"],
                 ["Baelmch", "", "", "cracked"]]
                """));

        assertEquals(2, result.rows().size());
        assertEquals("100000000000000099", result.rows().get(0).discordId);
        assertNull(result.rows().get(1).discordId);
        assertEquals("w0ving", result.rows().get(1).discordUsername);
        // "-" and the table without a Discord column both count as "no Discord".
        assertEquals(2, result.withoutDiscord());
        assertEquals(2, result.invalidDiscord().size());
        assertTrue(result.invalidDiscord().get(1).reason().contains("stored as a number"));
    }

    @Test
    void offlineUuidMatchesVanilla() {
        assertEquals(UUID.fromString("b50ad385-829d-3141-a216-7e7d7539ba7f"), MojangResolver.offlineUuid("Notch"));
    }
}
