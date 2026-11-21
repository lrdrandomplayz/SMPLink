package io.github.lrdrandomplayz.smplink.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import org.junit.jupiter.api.Test;

/**
 * Every slash command is registered in one call when the bot starts, so a single invalid definition
 * stops all of them from updating. Building and serialising them here catches that at build time.
 */
class CommandDefinitionsTest {

    @Test
    void everyCommandDefinitionIsValid() {
        List<CommandData> commands = BotListener.commands();
        commands.forEach(command -> assertTrue(command.toData().toMap().containsKey("name"), command.getName()));
        List<String> names = commands.stream().map(CommandData::getName).toList();
        assertEquals(names.stream().distinct().count(), names.size(), "duplicate command names: " + names);
        assertTrue(names.containsAll(List.of("profile", "playtime", "trade", "trades", "strike", "link", "unlink")), names.toString());
    }

    @Test
    void strikeRemoveAsksForTheMemberAndTheId() {
        var remove = StrikeCommand.definition().getSubcommands().stream().filter(s -> s.getName().equals("remove")).findFirst().orElseThrow();
        assertEquals(List.of("user", "id"), remove.getOptions().stream().map(o -> o.getName()).toList());
        assertTrue(remove.getOptions().stream().allMatch(o -> o.isRequired()));
    }
}
