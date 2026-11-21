package io.github.lrdrandomplayz.smplink.crash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class CrashSettingsTest {

    @Test
    void normalisesValues() {
        CrashSettings s = new CrashSettings(true, 3, 0, " 123 ", "off", true, 5, 99, true);
        assertEquals(10, s.snapshotSeconds());
        assertEquals(1, s.keep());
        assertEquals("123", s.staffChannelId());
        assertEquals("", s.announceChannelId());
        assertEquals(1.0, s.memoryAlert());
        assertEquals(20, s.tpsAlert());
        assertFalse(new CrashSettings(true, 30, 20, null, "  ", true, 0.9, 15, true).staffChannelSet());
    }

    @Test
    void missingSectionUsesDefaults() {
        assertEquals(CrashSettings.defaults(), CrashSettings.from(new YamlConfiguration()));
        assertEquals(CrashSettings.defaults(), CrashSettings.from(null));
        CrashSettings d = CrashSettings.defaults();
        assertEquals(30, d.snapshotSeconds());
        assertEquals(20, d.keep());
        assertEquals(0.90, d.memoryAlert());
        assertEquals(15, d.tpsAlert());
        assertTrue(d.relief() && d.healthEnabled() && d.enabled());
    }

    @Test
    void readsTheYamlSection() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                crash-recovery:
                  enabled: false
                  snapshot-seconds: 45
                  keep: 8
                  staff-channel-id: "111"
                  announce-channel-id: "222"
                  health:
                    enabled: false
                    memory-alert: 0.8
                    tps-alert: 12
                    relief: false
                """);
        CrashSettings s = CrashSettings.from(yaml);
        assertEquals(new CrashSettings(false, 45, 8, "111", "222", false, 0.8, 12, false), s);
    }
}
