package io.github.lrdrandomplayz.smplink.sync;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds rows under any header row that contains "MC Username". A header may also have
 * "Discord" (or "Discord ID") and "Minecraft Version" columns. A tab can hold several tables
 * stacked vertically; each header row resets the columns. The Discord cell may hold a user ID
 * or a username.
 */
public final class SheetParser {

    public enum Edition { PREMIUM, CRACKED }

    /** Exactly one of discordId and discordUsername is set. {@code table} is 0 for the first table on the tab. */
    public static final class Row {
        public final int row;
        public final int table;
        public final String ign;
        public final Edition edition;
        public String discordId;
        public final String discordUsername;

        Row(int row, int table, String ign, Edition edition, String discordId, String discordUsername) {
            this.row = row;
            this.table = table;
            this.ign = ign;
            this.edition = edition;
            this.discordId = discordId;
            this.discordUsername = discordUsername;
        }
    }

    public record Problem(int row, String ign, String value, String reason) {
    }

    /**
     * A table's header row and column positions (0-based; -1 when the table has no such column). {@code donorColumn}
     * is the "Donor?" column, where /whitelist add writes "Donor".
     */
    public record Table(int headerRow, int nameColumn, int discordColumn, int editionColumn, int width, int donorColumn) {
        public Table(int headerRow, int nameColumn, int discordColumn, int editionColumn, int width) {
            this(headerRow, nameColumn, discordColumn, editionColumn, width, -1);
        }
    }

    /** The first header cell that reads "Donor" (with or without a question mark), or -1. */
    static int donorColumn(List<String> lowerCells) {
        for (int i = 0; i < lowerCells.size(); i++) {
            if (lowerCells.get(i).replace("?", "").trim().equals("donor")) {
                return i;
            }
        }
        return -1;
    }

    /** Every row with a Minecraft name, whatever is in its Discord cell. */
    public record Entry(int row, int table, String ign, String discord) {
    }

    /** {@code allNames} is every Minecraft name on the tab, with or without Discord, for the whitelist. */
    public record Result(List<Row> rows, List<Problem> invalidDiscord, int withoutDiscord, List<String> allNames,
                         List<Table> tables, List<Entry> entries) {
    }

    private static final Pattern DISCORD_ID = Pattern.compile("\\d{17,20}");
    // The sheet uses "-" for players without Discord; treat those like an empty cell.
    private static final Pattern NO_VALUE = Pattern.compile("(|[-–—]+|n/?a|none)", Pattern.CASE_INSENSITIVE);
    // Current Discord usernames, optionally typed with a leading @ or an old-style #0 / #1234 tag.
    private static final Pattern DISCORD_USERNAME = Pattern.compile("@?([a-z0-9_.]{2,32})(?:#\\d{1,4})?",
            Pattern.CASE_INSENSITIVE);

    private SheetParser() {
    }

    public static Result parse(List<List<JsonElement>> sheet) {
        List<Row> rows = new ArrayList<>();
        List<Problem> invalid = new ArrayList<>();
        int withoutDiscord = 0;
        int nameColumn = -1;
        int discordColumn = -1;
        int editionColumn = -1;
        int table = -1;
        List<String> allNames = new ArrayList<>();
        List<Table> tables = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();

        for (int index = 0; index < sheet.size(); index++) {
            int rowNumber = index + 1;
            List<JsonElement> row = sheet.get(index);
            List<String> cells = new ArrayList<>(row.size());
            for (JsonElement cell : row) {
                cells.add(text(cell));
            }
            List<String> lower = cells.stream().map(c -> c.toLowerCase(Locale.ROOT)).toList();

            if (lower.contains("mc username")) {
                table++;
                nameColumn = lower.indexOf("mc username");
                discordColumn = lower.indexOf("discord") >= 0 ? lower.indexOf("discord") : lower.indexOf("discord id");
                editionColumn = lower.indexOf("minecraft version");
                tables.add(new Table(rowNumber, nameColumn, discordColumn, editionColumn, cells.size(), donorColumn(lower)));
                continue;
            }
            if (nameColumn < 0) {
                continue;
            }

            String ign = cell(cells, nameColumn);
            if (!ign.isEmpty()) {
                allNames.add(ign);
            }
            if (ign.isEmpty()) {
                continue;
            }

            JsonElement rawDiscord = discordColumn >= 0 && discordColumn < row.size() ? row.get(discordColumn) : null;
            String value = rawDiscord == null ? "" : text(rawDiscord);
            entries.add(new Entry(rowNumber, table, ign, value));
            if (NO_VALUE.matcher(value).matches()) {
                withoutDiscord++;
                continue;
            }
            if (rawDiscord.isJsonPrimitive() && rawDiscord.getAsJsonPrimitive().isNumber()) {
                invalid.add(new Problem(rowNumber, ign, value, "is stored as a number, so the last digits were lost. "
                        + "Format the Discord column as Plain text and re-enter the ID"));
                continue;
            }

            String editionText = cell(cells, editionColumn).toLowerCase(Locale.ROOT);
            Edition edition = switch (editionText) {
                case "premium" -> Edition.PREMIUM;
                case "cracked" -> Edition.CRACKED;
                default -> null;
            };

            if (DISCORD_ID.matcher(value).matches()) {
                rows.add(new Row(rowNumber, table, ign, edition, value, null));
                continue;
            }
            Matcher username = DISCORD_USERNAME.matcher(value);
            if (username.matches()) {
                rows.add(new Row(rowNumber, table, ign, edition, null, username.group(1).toLowerCase(Locale.ROOT)));
            } else {
                invalid.add(new Problem(rowNumber, ign, value, "is neither a Discord user ID nor a username"));
            }
        }
        return new Result(rows, invalid, withoutDiscord, allNames, tables, entries);
    }

    private static String cell(List<String> cells, int column) {
        return column >= 0 && column < cells.size() ? cells.get(column) : "";
    }

    private static String text(JsonElement cell) {
        if (cell == null || cell.isJsonNull()) {
            return "";
        }
        if (cell.isJsonPrimitive() && cell.getAsJsonPrimitive().isNumber()) {
            // Plain toString, so an ID typed into a Number cell shows as the digits the sheet kept.
            return cell.getAsJsonPrimitive().getAsBigDecimal().toPlainString();
        }
        return cell.isJsonPrimitive() ? cell.getAsString().trim() : cell.toString();
    }
}
