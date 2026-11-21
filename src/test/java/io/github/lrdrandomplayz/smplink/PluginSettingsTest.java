package io.github.lrdrandomplayz.smplink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PluginSettingsTest {

    @SuppressWarnings("unchecked")
    private static Map<Integer, String> milestoneRoles(FileConfiguration config) throws Exception {
        Method method = PluginSettings.class.getDeclaredMethod("milestoneRoles", FileConfiguration.class, Logger.class);
        method.setAccessible(true);
        return (Map<Integer, String>) method.invoke(null, config, Logger.getLogger("test"));
    }

    @Test
    void readsMilestoneRolesInOrderOfHours() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("milestones:\n  roles:\n    200: '444'\n    24: '111'\n    50: \"222\"\n    oops: '9'\n");
        Map<Integer, String> roles = milestoneRoles(config);
        assertEquals(List.of(24, 50, 200), List.copyOf(roles.keySet()));
        assertEquals("111", roles.get(24));
        assertEquals("444", roles.get(200));
    }

    @Test
    void theShippedConfigHasNoServerSpecificIds() throws Exception {
        YamlConfiguration shipped = YamlConfiguration.loadConfiguration(new InputStreamReader(
                PluginSettingsTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        assertTrue(milestoneRoles(shipped).isEmpty(), "milestone roles must be empty until the admin sets them");
        for (String key : shipped.getKeys(true)) {
            Object value = shipped.get(key);
            if (value instanceof String text) {
                assertTrue(!text.matches(".*[0-9]{17,20}.*"), key + " holds a Discord ID: " + text);
            }
        }
    }
}
