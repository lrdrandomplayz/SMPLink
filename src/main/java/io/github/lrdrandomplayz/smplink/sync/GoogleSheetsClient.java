package io.github.lrdrandomplayz.smplink.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Reads a sheet with a Google service account. Signing the OAuth assertion by hand keeps the
 * whole Google client library (several MB) out of the plugin jar.
 */
public final class GoogleSheetsClient {

    /** {@code gid} is the tab's numeric ID, needed to delete rows. */
    public record Sheet(String tab, int gid, List<List<JsonElement>> rows) {
    }

    // Read and write: /whitelist add and remove edit the sheet. Writing also needs the service account
    // to be an Editor on the sheet, not just a Viewer.
    private static final String SCOPE = "https://www.googleapis.com/auth/spreadsheets";
    private static final String API = "https://sheets.googleapis.com/v4/spreadsheets/";

    private final HttpClient http;
    private final Path keyFile;
    private String accessToken;
    private long tokenExpiresAt;

    public GoogleSheetsClient(HttpClient http, Path keyFile) {
        this.http = http;
        this.keyFile = keyFile;
    }

    public synchronized Sheet read(String sheetId, String tab) throws IOException, InterruptedException {
        JsonObject meta = get(API + sheetId + "?fields=sheets.properties(title,sheetId)");
        JsonArray sheets = meta.getAsJsonArray("sheets");
        if (sheets == null || sheets.isEmpty()) {
            throw new IOException("The spreadsheet has no tabs.");
        }
        String title = null;
        int gid = 0;
        for (JsonElement element : sheets) {
            JsonObject properties = element.getAsJsonObject().getAsJsonObject("properties");
            String name = properties.get("title").getAsString();
            if (tab == null || tab.isEmpty() || tab.equals(name)) {
                title = name;
                gid = properties.has("sheetId") ? properties.get("sheetId").getAsInt() : 0;
                break;
            }
        }
        if (title == null) {
            throw new IOException("The spreadsheet has no tab called \"" + tab + "\".");
        }

        String range = "'" + title.replace("'", "''") + "'!A1:Z";
        JsonObject values = get(API + sheetId + "/values/" + encode(range) + "?valueRenderOption=UNFORMATTED_VALUE");
        List<List<JsonElement>> rows = new ArrayList<>();
        JsonArray rawRows = values.getAsJsonArray("values");
        if (rawRows != null) {
            for (JsonElement rawRow : rawRows) {
                List<JsonElement> row = new ArrayList<>();
                rawRow.getAsJsonArray().forEach(row::add);
                rows.add(row);
            }
        }
        return new Sheet(title, gid, rows);
    }

    /**
     * Appends a row after the table whose header row is {@code headerRow}, inserting a new sheet row so
     * anything below is pushed down rather than overwritten. Values are stored exactly as given, so a
     * Discord ID stays text instead of being rounded as a number. Returns the new row's number.
     */
    public synchronized int appendRow(String sheetId, String tab, int headerRow, List<String> values)
            throws IOException, InterruptedException {
        String lastColumn = String.valueOf((char) ('A' + Math.max(0, values.size() - 1)));
        String range = "'" + tab.replace("'", "''") + "'!A" + headerRow + ":" + lastColumn;
        JsonObject body = new JsonObject();
        JsonArray row = new JsonArray();
        values.forEach(row::add);
        JsonArray rows = new JsonArray();
        rows.add(row);
        body.add("values", rows);
        JsonObject response = post(API + sheetId + "/values/" + encode(range)
                + ":append?valueInputOption=RAW&insertDataOption=INSERT_ROWS", body);
        String updated = response.getAsJsonObject("updates").get("updatedRange").getAsString();
        return Integer.parseInt(updated.replaceAll("^.*![A-Z]+([0-9]+).*$", "$1"));
    }

    /** Deletes one row (1-based) from the tab; rows below move up. */
    public synchronized void deleteRow(String sheetId, int gid, int row) throws IOException, InterruptedException {
        JsonObject range = new JsonObject();
        range.addProperty("sheetId", gid);
        range.addProperty("dimension", "ROWS");
        range.addProperty("startIndex", row - 1);
        range.addProperty("endIndex", row);
        JsonObject delete = new JsonObject();
        delete.add("range", range);
        JsonObject request = new JsonObject();
        request.add("deleteDimension", delete);
        JsonArray requests = new JsonArray();
        requests.add(request);
        JsonObject body = new JsonObject();
        body.add("requests", requests);
        post(API + sheetId + ":batchUpdate", body);
    }

    /**
     * The background colours of one column from {@code fromRow} to {@code toRow} (1-based), as {r, g, b} from 0 to 1;
     * a row with no colour set is white. {@code column} is 0-based.
     */
    public synchronized List<float[]> backgrounds(String sheetId, String tab, int column, int fromRow, int toRow)
            throws IOException, InterruptedException {
        String letter = String.valueOf((char) ('A' + column));
        String range = "'" + tab.replace("'", "''") + "'!" + letter + fromRow + ":" + letter + toRow;
        JsonObject response = get(API + sheetId + "?ranges=" + encode(range)
                + "&fields=" + encode("sheets.data.rowData.values.effectiveFormat.backgroundColor"));
        List<float[]> colours = new ArrayList<>();
        JsonArray sheets = response.getAsJsonArray("sheets");
        JsonArray data = sheets == null || sheets.isEmpty() ? null : sheets.get(0).getAsJsonObject().getAsJsonArray("data");
        JsonArray rows = data == null || data.isEmpty() ? null : data.get(0).getAsJsonObject().getAsJsonArray("rowData");
        for (int i = 0; i <= toRow - fromRow; i++) {
            float[] rgb = {1, 1, 1};
            if (rows != null && i < rows.size()) {
                JsonObject row = rows.get(i).getAsJsonObject();
                JsonArray values = row.getAsJsonArray("values");
                if (values != null && !values.isEmpty()) {
                    JsonObject format = values.get(0).getAsJsonObject().getAsJsonObject("effectiveFormat");
                    JsonObject bg = format == null ? null : format.getAsJsonObject("backgroundColor");
                    if (bg != null) {
                        // Google leaves out channels that are 0.
                        rgb = new float[]{channel(bg, "red"), channel(bg, "green"), channel(bg, "blue")};
                    }
                }
            }
            colours.add(rgb);
        }
        return colours;
    }

    private static float channel(JsonObject colour, String name) {
        return colour.has(name) ? colour.get(name).getAsFloat() : 0f;
    }

    /**
     * Changes one cell (1-based row, 0-based column): its text when {@code text} isn't null, and its background
     * when {@code rgb} isn't null (the rest of its formatting is kept).
     */
    public synchronized void updateCell(String sheetId, int gid, int row, int column, String text, float[] rgb)
            throws IOException, InterruptedException {
        JsonObject cell = new JsonObject();
        List<String> fields = new ArrayList<>();
        if (text != null) {
            JsonObject value = new JsonObject();
            value.addProperty("stringValue", text);
            cell.add("userEnteredValue", value);
            fields.add("userEnteredValue");
        }
        if (rgb != null) {
            JsonObject colour = new JsonObject();
            colour.addProperty("red", rgb[0]);
            colour.addProperty("green", rgb[1]);
            colour.addProperty("blue", rgb[2]);
            JsonObject format = new JsonObject();
            format.add("backgroundColor", colour);
            cell.add("userEnteredFormat", format);
            fields.add("userEnteredFormat.backgroundColor");
        }
        if (fields.isEmpty()) {
            return;
        }
        JsonArray values = new JsonArray();
        values.add(cell);
        JsonObject rowData = new JsonObject();
        rowData.add("values", values);
        JsonArray rows = new JsonArray();
        rows.add(rowData);
        JsonObject start = new JsonObject();
        start.addProperty("sheetId", gid);
        start.addProperty("rowIndex", row - 1);
        start.addProperty("columnIndex", column);
        JsonObject update = new JsonObject();
        update.add("start", start);
        update.add("rows", rows);
        update.addProperty("fields", String.join(",", fields));
        JsonObject request = new JsonObject();
        request.add("updateCells", update);
        JsonArray requests = new JsonArray();
        requests.add(request);
        JsonObject body = new JsonObject();
        body.add("requests", requests);
        post(API + sheetId + ":batchUpdate", body);
    }

    private JsonObject post(String url, JsonObject body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            String message = errorMessage(response.body(), response.statusCode());
            if (response.statusCode() == 403) {
                message += " (the service account must be an Editor on the sheet to change it)";
            }
            throw new IOException("Google Sheets: " + message);
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private JsonObject get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token())
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Google Sheets: " + errorMessage(response.body(), response.statusCode()));
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private String token() throws IOException, InterruptedException {
        long now = System.currentTimeMillis() / 1000;
        if (accessToken != null && now < tokenExpiresAt - 60) {
            return accessToken;
        }
        if (!Files.exists(keyFile)) {
            throw new IOException("Service account key not found at " + keyFile);
        }
        JsonObject key = JsonParser.parseString(Files.readString(keyFile)).getAsJsonObject();
        String clientEmail = key.get("client_email").getAsString();
        String tokenUri = key.has("token_uri") ? key.get("token_uri").getAsString() : "https://oauth2.googleapis.com/token";

        JsonObject header = new JsonObject();
        header.addProperty("alg", "RS256");
        header.addProperty("typ", "JWT");
        JsonObject claims = new JsonObject();
        claims.addProperty("iss", clientEmail);
        claims.addProperty("scope", SCOPE);
        claims.addProperty("aud", tokenUri);
        claims.addProperty("iat", now);
        claims.addProperty("exp", now + 3600);
        String unsigned = base64Url(header.toString().getBytes(StandardCharsets.UTF_8)) + "."
                + base64Url(claims.toString().getBytes(StandardCharsets.UTF_8));
        String assertion = unsigned + "." + base64Url(sign(unsigned, key.get("private_key").getAsString()));

        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUri))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=" + encode("urn:ietf:params:oauth:grant-type:jwt-bearer") + "&assertion=" + assertion))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Google sign-in with the service account failed: "
                    + errorMessage(response.body(), response.statusCode()));
        }
        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        accessToken = body.get("access_token").getAsString();
        tokenExpiresAt = now + body.get("expires_in").getAsLong();
        return accessToken;
    }

    private static byte[] sign(String data, String pem) throws IOException {
        try {
            String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            PrivateKey privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            return signature.sign();
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("The service account key's private_key could not be read", e);
        }
    }

    private static String errorMessage(String body, int status) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            JsonElement error = json.get("error");
            if (error != null && error.isJsonObject() && error.getAsJsonObject().has("message")) {
                return error.getAsJsonObject().get("message").getAsString();
            }
            if (json.has("error_description")) {
                return json.get("error_description").getAsString();
            }
        } catch (RuntimeException ignored) {
            // Not JSON; fall back to the status code.
        }
        return "HTTP " + status;
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
