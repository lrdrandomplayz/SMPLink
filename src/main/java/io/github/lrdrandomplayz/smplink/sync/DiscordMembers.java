package io.github.lrdrandomplayz.smplink.sync;

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
import java.time.Duration;

/**
 * Finds a member's user ID from their username through Discord's member search endpoint.
 * Called over plain HTTP because the gateway version needs the privileged Server Members intent.
 */
public final class DiscordMembers {

    private final HttpClient http;
    private final String token;
    private final String guildId;

    public DiscordMembers(HttpClient http, String token, String guildId) {
        this.http = http;
        this.token = token;
        this.guildId = guildId;
    }

    /** Returns the user ID, or null when no member of the guild has exactly that username. */
    public String findByUsername(String username) throws IOException, InterruptedException {
        URI uri = URI.create("https://discord.com/api/v10/guilds/" + guildId + "/members/search?limit=10&query="
                + URLEncoder.encode(username, StandardCharsets.UTF_8));
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bot " + token)
                    .header("User-Agent", "DiscordBot (https://github.com/lrdrandomplayz/SMPLink, 0.1)")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                double retryAfter = JsonParser.parseString(response.body()).getAsJsonObject().get("retry_after").getAsDouble();
                Thread.sleep((long) Math.ceil(retryAfter * 1000));
                continue;
            }
            if (response.statusCode() != 200) {
                throw new IOException("Discord member search returned HTTP " + response.statusCode());
            }
            for (JsonElement element : JsonParser.parseString(response.body()).getAsJsonArray()) {
                JsonObject user = element.getAsJsonObject().getAsJsonObject("user");
                if (user.get("username").getAsString().equalsIgnoreCase(username)) {
                    return user.get("id").getAsString();
                }
            }
            return null;
        }
        throw new IOException("Discord kept rate limiting the member search");
    }
}
