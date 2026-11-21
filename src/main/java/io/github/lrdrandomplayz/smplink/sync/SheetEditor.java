package io.github.lrdrandomplayz.smplink.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import io.github.lrdrandomplayz.smplink.PluginSettings;
import io.github.lrdrandomplayz.smplink.sync.MojangResolver.Failed;
import io.github.lrdrandomplayz.smplink.sync.MojangResolver.Found;
import io.github.lrdrandomplayz.smplink.sync.MojangResolver.Lookup;
import io.github.lrdrandomplayz.smplink.sync.SheetParser.Entry;
import io.github.lrdrandomplayz.smplink.sync.SheetParser.Table;

/**
 * Adds and removes members in the Google Sheet for /whitelist. The sheet stays the source of truth:
 * after an edit the caller runs a sync, which links, whitelists or removes the player as usual.
 */
public final class SheetEditor {

    /** A problem the admin can fix (bad name, duplicate, and so on); its message is shown as-is. */
    public static final class EditException extends Exception {
        public EditException(String message) {
            super(message);
        }
    }

    /** {@code donor} is whether "Donor" was written; {@code noDonorColumn} is set when it should have been but there's no column. */
    public record Added(int row, String ign, String edition, boolean donor, boolean noDonorColumn) {
    }

    /** A sheet row picked for removal. {@code table} 0 is the original members' table. */
    public record Match(int row, int table, String ign, String discord) {
    }

    private final PluginSettings settings;
    private final GoogleSheetsClient sheets;
    private final MojangResolver mojang;

    public SheetEditor(PluginSettings settings, GoogleSheetsClient sheets, MojangResolver mojang) {
        this.settings = settings;
        this.sheets = sheets;
        this.mojang = mojang;
    }

    /**
     * Adds a row to the last table on the tab (the new members' table). A premium name must exist on
     * Mojang, and it is written with Mojang's capitalisation. Call off the main thread.
     */
    public Added addMember(String discordId, String edition, String ign, boolean donor) throws Exception {
        String name = ign.trim();
        if (!MojangResolver.VALID_NAME.matcher(name).matches()) {
            throw new EditException("`" + name + "` isn't a valid Minecraft name (3–16 letters, numbers or _).");
        }
        if (edition.equals("premium")) {
            Lookup lookup = mojang.resolve(name);
            if (lookup instanceof Found found) {
                name = found.name();
            } else if (lookup instanceof Failed failed) {
                throw new EditException("Couldn't check `" + name + "` with Mojang (" + failed.message()
                        + "). Try again in a minute.");
            } else {
                throw new EditException("Mojang has no premium account called `" + name
                        + "`. Check the spelling, or add them as cracked.");
            }
        }

        GoogleSheetsClient.Sheet sheet = sheets.read(settings.sheetId(), settings.sheetTab());
        SheetParser.Result parsed = SheetParser.parse(sheet.rows());
        String key = name.toLowerCase(Locale.ROOT);
        for (Entry entry : parsed.entries()) {
            if (entry.ign().toLowerCase(Locale.ROOT).equals(key)) {
                throw new EditException("`" + entry.ign() + "` is already in the sheet (row " + entry.row() + ").");
            }
            if (entry.discord().equals(discordId)) {
                throw new EditException("<@" + discordId + "> is already in the sheet as `" + entry.ign()
                        + "` (row " + entry.row() + ").");
            }
        }
        if (parsed.tables().isEmpty()) {
            throw new EditException("The sheet has no table with an \"MC Username\" header to add to.");
        }
        Table table = parsed.tables().get(parsed.tables().size() - 1);
        if (table.discordColumn() < 0) {
            throw new EditException("The new members' table (row " + table.headerRow() + ") has no Discord column.");
        }

        boolean writeDonor = donor && table.donorColumn() >= 0;
        int width = Math.max(Math.max(Math.max(table.nameColumn(), table.discordColumn()), table.editionColumn()),
                writeDonor ? table.donorColumn() : -1) + 1;
        List<String> values = new ArrayList<>(Collections.nCopies(width, ""));
        values.set(table.nameColumn(), name);
        values.set(table.discordColumn(), discordId);
        if (table.editionColumn() >= 0) {
            values.set(table.editionColumn(), edition);
        }
        // Donors get "Donor" in the "Donor?" column; everyone else leaves it blank.
        if (writeDonor) {
            values.set(table.donorColumn(), "Donor");
        }
        int row = sheets.appendRow(settings.sheetId(), sheet.tab(), table.headerRow(), values);
        return new Added(row, name, edition, writeDonor, donor && !writeDonor);
    }

    /** The Sheets client, for payments (reading cell colours, recolouring cells, moving rows). */
    public GoogleSheetsClient client() {
        return sheets;
    }

    /** Finds a member by Discord ID or Minecraft name (either may be null); null when they're not in the sheet. */
    public Match find(String discordId, String ign) throws Exception {
        GoogleSheetsClient.Sheet sheet = sheets.read(settings.sheetId(), settings.sheetTab());
        for (Entry entry : SheetParser.parse(sheet.rows()).entries()) {
            boolean byDiscord = discordId != null && entry.discord().equals(discordId);
            boolean byName = ign != null && entry.ign().equalsIgnoreCase(ign.trim());
            if (byDiscord || byName) {
                return new Match(entry.row(), entry.table(), entry.ign(), entry.discord());
            }
        }
        return null;
    }

    /**
     * Deletes the member's row. The sheet is read again first and the row must still hold that name,
     * so an edit made meanwhile (which shifts row numbers) can't delete the wrong person.
     */
    public void remove(int row, String ign) throws Exception {
        GoogleSheetsClient.Sheet sheet = sheets.read(settings.sheetId(), settings.sheetTab());
        boolean stillThere = SheetParser.parse(sheet.rows()).entries().stream()
                .anyMatch(entry -> entry.row() == row && entry.ign().equalsIgnoreCase(ign));
        if (!stillThere) {
            throw new EditException("The sheet changed since you asked; `" + ign + "` is no longer on row " + row
                    + ". Run /whitelist remove again.");
        }
        sheets.deleteRow(settings.sheetId(), sheet.gid(), row);
    }
}
