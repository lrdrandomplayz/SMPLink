package io.github.lrdrandomplayz.smplink.discord;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import io.github.lrdrandomplayz.smplink.PluginSettings;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.Strike;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;
import io.github.lrdrandomplayz.smplink.service.PlayerService;

/**
 * Strikes: from missed activity-check deadlines, offline raiding and /strike add. Each counts for
 * strikes.expire-days; reaching a member's limit (strikes.limit, or strikes.extended-limit with one of
 * strikes.extended-roles) permanently removes them.
 */
final class Strikes {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    // Roles are read from Discord when a limit or exemption is needed; a few minutes' cache is plenty.
    private static final long ROLE_CACHE_MS = 5 * 60 * 1000;

    private record CachedRoles(Set<String> roles, long fetchedAt) {
    }

    private final TrackingRepository tracking;
    private final PluginSettings settings;
    private final Map<String, CachedRoles> roleCache = new ConcurrentHashMap<>();


    /** What a raid strike counts as unless strikes.raid-weight says otherwise. */
    static final int DEFAULT_RAID_WEIGHT = 2;
    private volatile int raidWeight = DEFAULT_RAID_WEIGHT;

    /** strikes.raid-weight: how many strikes one offline-raiding strike counts as (at least 1). */
    void setRaidWeight(int raidWeight) {
        this.raidWeight = Math.max(1, raidWeight);
    }

    int raidWeight() {
        return raidWeight;
    }

    /** The strikes' worth of {@code active}: raid strikes count {@link #raidWeight()}, others 1. This is what's compared with the limit. */
    int weighted(List<Strike> active) {
        return weighted(active, raidWeight);
    }

    static int weighted(List<Strike> active, int raidWeight) {
        int total = 0;
        for (Strike strike : active) {
            total += "raid".equals(strike.kind()) ? Math.max(1, raidWeight) : 1;
        }
        return total;
    }

    /** "(counts as 2)" for a raid strike, otherwise empty. */
    String countsNote(String kind) {
        return "raid".equals(kind) && raidWeight > 1 ? "(counts as " + raidWeight + ")" : "";
    }
    Strikes(TrackingRepository tracking, PluginSettings settings) {
        this.tracking = tracking;
        this.settings = settings;
    }

    /**
     * The member's strike limit: the extended limit if they have one of its roles. If Discord can't be
     * asked right now the higher limit is used, so nobody is removed early because of a network hiccup.
     */
    int limit(JDA jda, String discordId) {
        Set<String> roles = roles(jda, discordId);
        boolean extended = roles == null || settings.strikeExtendedRoles().stream().anyMatch(roles::contains);
        return extended ? Math.max(settings.strikeLimit(), settings.strikeExtendedLimit()) : settings.strikeLimit();
    }

    /**
     * The roles the activity check skips: activity-check.exempt-roles, plus the admin role, which is always exempt.
     */
    List<String> activityExemptRoles() {
        return exemptRoles(settings.activityExemptRoles(), settings.adminRoleId());
    }

    static List<String> exemptRoles(List<String> configured, String... alwaysExempt) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>(configured);
        for (String id : alwaysExempt) {
            if (id != null && !id.isBlank()) {
                ids.add(id.trim());
            }
        }
        return List.copyOf(ids);
    }

    /** True for members the activity check skips: the server owner, and anyone with an exempt role. */
    boolean exemptFromActivity(JDA jda, String discordId) {
        if (isOwner(jda, discordId)) {
            return true;
        }
        Set<String> roles = roles(jda, discordId);
        return roles != null && activityExemptRoles().stream().anyMatch(roles::contains);
    }

    /** Why the member is exempt, e.g. "Donor" or "Server owner", or null. */
    String exemptRoleName(JDA jda, String discordId) {
        Guild guild = jda == null ? null : jda.getGuildById(settings.guildId());
        if (guild == null) {
            return null;
        }
        if (discordId.equals(guild.getOwnerId())) {
            return "Server owner";
        }
        Set<String> roles = roles(jda, discordId);
        if (roles == null) {
            return null;
        }
        for (String id : activityExemptRoles()) {
            if (roles.contains(id)) {
                Role role = guild.getRoleById(id);
                return role == null ? "exempt role" : role.getName();
            }
        }
        return null;
    }

    private boolean isOwner(JDA jda, String discordId) {
        Guild guild = jda == null ? null : jda.getGuildById(settings.guildId());
        return guild != null && discordId.equals(guild.getOwnerId());
    }

    /** The member's role IDs (none if they've left the server), or null if Discord can't be asked right now. */
    private Set<String> roles(JDA jda, String discordId) {
        CachedRoles cached = roleCache.get(discordId);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.fetchedAt() < ROLE_CACHE_MS) {
            return cached.roles();
        }
        Guild guild = jda == null ? null : jda.getGuildById(settings.guildId());
        if (guild == null) {
            return null;
        }
        Set<String> roles;
        try {
            roles = guild.retrieveMemberById(discordId).complete().getRoles().stream()
                    .map(Role::getId).collect(Collectors.toUnmodifiableSet());
        } catch (ErrorResponseException e) {
            // Not in the server (any more): no roles. Other failures aren't cached, so they're retried.
            if (e.getErrorCode() != 10007 && e.getErrorCode() != 10013) {
                return null;
            }
            roles = Set.of();
        } catch (RuntimeException e) {
            return null;
        }
        roleCache.put(discordId, new CachedRoles(roles, now));
        return roles;
    }

    long expireMs() {
        return settings.strikeExpireDays() * DAY_MS;
    }

    /** Strikes still counting, oldest first. */
    List<Strike> active(String discordId) throws Exception {
        return SyncBridge.await(tracking.strikesSince(discordId, System.currentTimeMillis() - expireMs()),
                PlayerService.DB_TIMEOUT_MS);
    }

    /** "3 months" for multiples of 30 days, otherwise "45 days". */
    String expiryText() {
        return expiryText(settings.strikeExpireDays());
    }

    static String expiryText(int days) {
        if (days % 30 == 0) {
            int months = days / 30;
            return months + (months == 1 ? " month" : " months");
        }
        return days + (days == 1 ? " day" : " days");
    }

    /** "❗ Strikes: **1/3** · next one expires in 2 months", or "Strikes: 0/3". */
    String line(List<Strike> active, int limit) {
        if (active.isEmpty()) {
            return "Strikes: **0/" + limit + "**";
        }
        long nextExpiry = active.get(0).givenAt() + expireMs();
        return Glyph.ALERT + " Strikes: **" + weighted(active) + "/" + limit + "** · next one expires <t:" + nextExpiry / 1000 + ":R>";
    }

    /** Fills {strikes}, {max-strikes} and {strike-expiry} in a DM template. */
    String fill(String template, int strikes, int limit) {
        return template.replace("{strikes}", String.valueOf(strikes))
                .replace("{max-strikes}", String.valueOf(limit))
                .replace("{strike-expiry}", expiryText());
    }

    static String kindEmoji(String kind) {
        return switch (kind) {
            case "activity" -> Glyph.ACTIVITY.toString();
            case "raid" -> Glyph.RAID.toString();
            default -> Glyph.STRIKE.toString();
        };
    }

    /** "`#12` 🏴‍☠️ Offline raiding Steve's base at 120, -40", with long reasons shortened for lists. */
    static String describe(Strike strike) {
        String reason = strike.reason().length() > 90 ? strike.reason().substring(0, 87) + "…" : strike.reason();
        return "`#" + strike.id() + "` " + kindEmoji(strike.kind()) + " " + reason;
    }
}
