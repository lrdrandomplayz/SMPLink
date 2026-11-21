package io.github.lrdrandomplayz.smplink.command;

import io.github.lrdrandomplayz.smplink.sync.SheetImporter;
import java.util.List;
import java.util.concurrent.CompletionException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

/** /smplink sync runs the Google Sheet import now (linking.mode: sheet). Works from the console or in game. */
public final class SyncCommand implements TabExecutor {

    private static final String USAGE = "Usage: /smplink sync";

    private final JavaPlugin plugin;
    private final SheetImporter importer;

    public SyncCommand(JavaPlugin plugin, SheetImporter importer) {
        this.plugin = plugin;
        this.importer = importer;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1 || !args[0].equalsIgnoreCase("sync")) {
            sender.sendMessage(Component.text(USAGE, NamedTextColor.RED));
            return true;
        }
        if (importer == null) {
            sender.sendMessage(Component.text("Sheet sync is off. Set linking.mode: sheet and sheet.id in config.yml.", NamedTextColor.RED));
            return true;
        }
        sender.sendMessage(Component.text("Importing links from the sheet…", NamedTextColor.GRAY));
        importer.run("/smplink sync by " + sender.getName()).whenComplete((report, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                    sender.sendMessage(Component.text("Import failed: " + cause.getMessage(), NamedTextColor.RED));
                    return;
                }
                var result = report.result();
                sender.sendMessage(Component.text("Import done: " + result.inserted() + " inserted, " + result.updated()
                        + " updated, " + result.skipped() + " skipped, " + report.rejected()
                        + " rows rejected (details in the console).", NamedTextColor.GREEN));
            });
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("sync") : List.of();
    }
}
