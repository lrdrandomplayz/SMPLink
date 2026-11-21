package io.github.lrdrandomplayz.smplink.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsFolderTest {

    @Test
    void usesPlayersStatsOnMinecraft26(@TempDir Path world) throws Exception {
        Files.createDirectories(world.resolve("players").resolve("stats"));
        Files.createDirectories(world.resolve("stats")); // an old folder left behind by the upgrade
        assertEquals(world.resolve("players").resolve("stats"), StatsReader.statsFolder(world));
    }

    @Test
    void fallsBackToTheOldFolder(@TempDir Path world) throws Exception {
        Files.createDirectories(world.resolve("stats"));
        assertEquals(world.resolve("stats"), StatsReader.statsFolder(world));
    }

    @Test
    void defaultsToPlayersStatsWhenNeitherExistsYet(@TempDir Path world) {
        assertEquals(world.resolve("players").resolve("stats"), StatsReader.statsFolder(world));
    }

    @Test
    void findsPlayersStatsAboveTheDimensionFolder(@TempDir Path world) throws Exception {
        Path overworld = world.resolve("dimensions").resolve("minecraft").resolve("overworld");
        Files.createDirectories(overworld);
        Files.createDirectories(world.resolve("players").resolve("stats"));
        assertEquals(world.resolve("players").resolve("stats"), StatsReader.statsFolder(overworld));
    }
}
