package io.github.lrdrandomplayz.smplink.discord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.PermissionException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import io.github.lrdrandomplayz.smplink.PluginSettings;
import io.github.lrdrandomplayz.smplink.db.LinkRepository.Link;
import io.github.lrdrandomplayz.smplink.service.PlayerService;

/**
 * Gives linked members the linked role and sets their nickname to their Minecraft name, and undoes both
 * for players removed from the sheet or moved to another Discord account. Removals that couldn't reach Discord
 * are kept and retried. Every few hours a sweep also takes the linked role from anyone holding it who isn't linked
 * (given by hand, or left over from before); that needs the Server Members Intent switched on in the Developer
 * Portal, and is skipped with a warning if it isn't. Members are otherwise fetched one at a time over REST, so no
 * gateway intent is needed. Blocks on Discord; run it on a worker thread.
 *
 * <p>The Original Supporter role follows the sheet's first table the same way: given to linked Original Supporters,
 * taken from members moved to the new-members table or removed from the sheet, and swept from anyone else holding it
 * (the server owner and admins are left alone).
 */
final class MemberSync {

    private static final long FULL_RECHECK_MS = 6L * 60 * 60 * 1000;

    private final PluginSettings settings;
    private final PlayerService players;
    private final Logger logger;
    // What was last applied per Discord ID, so a sync only touches members whose link changed.
    private final Map<String, String> applied = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    // Unlinked members whose roles couldn't be taken yet (the bot wasn't connected); retried on the next run.
    private final List<Link> pending = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(15)).build();
    private volatile long lastFullCheck;
    // Role for the sheet's first table (Original Supporters); "" = not managed.
    private volatile String originalRoleId = "";

    MemberSync(PluginSettings settings, PlayerService players, Logger logger) {
        this.settings = settings;
        this.players = players;
        this.logger = logger;
    }

    boolean enabled() {
        return !settings.linkedRoleId().isEmpty() || settings.nicknameSync() || !settings.milestoneRoles().isEmpty()
                || !originalRoleId.isEmpty();
    }

    /** Sets the Original Supporter role ({@code discord.original-role-id}); blank or "off" leaves that role alone. */
    void setOriginalRoleId(String roleId) {
        String id = roleId == null ? "" : roleId.trim();
        originalRoleId = id.equalsIgnoreCase("off") || !id.matches("\\d{5,25}") ? "" : id;
        lastFullCheck = 0;
    }

    /**
     * Milestone role IDs a player with {@code seconds} of all-time playtime should have. {@code roles} is
     * ordered by hours. Always returns a new, modifiable set, because the caller narrows it further.
     */
    static Set<String> milestoneRolesFor(Map<Integer, String> roles, boolean highestOnly, long seconds) {
        List<String> reached = new ArrayList<>();
        roles.forEach((hours, roleId) -> {
            if (seconds >= hours * 3600L) {
                reached.add(roleId);
            }
        });
        if (highestOnly && reached.size() > 1) {
            return new HashSet<>(List.of(reached.get(reached.size() - 1)));
        }
        return new HashSet<>(reached);
    }

    synchronized void run(JDA jda, List<Link> removed) throws Exception {
        if (!enabled()) {
            return;
        }
        Guild guild = jda == null ? null : jda.getGuildById(settings.guildId());
        if (guild == null) {
            pending.addAll(removed);
            return;
        }
        List<Link> toUndo = new ArrayList<>(pending);
        toUndo.addAll(removed);
        pending.clear();
        Role role = settings.linkedRoleId().isEmpty() ? null : guild.getRoleById(settings.linkedRoleId());
        if (!settings.linkedRoleId().isEmpty() && role == null) {
            warnOnce("role", "discord.linked-role-id " + settings.linkedRoleId() + " is not a role in this server");
        }
        Role originalRole = originalRoleId.isEmpty() ? null : guild.getRoleById(originalRoleId);
        if (!originalRoleId.isEmpty() && originalRole == null) {
            warnOnce("original-role", "discord.original-role-id " + originalRoleId + " is not a role in this server");
        }
        // Members can lose the role or change their nickname by hand, so everyone is rechecked now and then.
        boolean fullCheck = System.currentTimeMillis() - lastFullCheck > FULL_RECHECK_MS;
        if (fullCheck) {
            applied.clear();
            lastFullCheck = System.currentTimeMillis();
        }

        Map<UUID, Long> playtime = new HashMap<>();
        if (!settings.milestoneRoles().isEmpty()) {
            players.allTimeRanking().forEach(entry -> playtime.put(entry.uuid(), entry.playtimeSeconds()));
        }
        Map<String, Role> milestoneRoles = new HashMap<>();
        settings.milestoneRoles().forEach((hours, roleId) -> {
            Role milestoneRole = guild.getRoleById(roleId);
            if (milestoneRole == null) {
                warnOnce("milestone-" + hours, "milestones.roles " + hours + " (" + roleId + ") is not a role in this server");
            } else {
                milestoneRoles.put(roleId, milestoneRole);
            }
        });

        List<Link> links = players.links();
        Set<String> linked = new HashSet<>();
        Set<String> originals = new HashSet<>();
        for (Link link : links) {
            linked.add(link.discordId());
            if (link.original()) {
                originals.add(link.discordId());
            }
        }
        for (Link link : links) {
            try {
                syncMember(guild, link, role, originalRole, originals.contains(link.discordId()), milestoneRoles,
                        playtime.getOrDefault(link.uuid(), 0L));
            } catch (RuntimeException e) {
                // One member's failure (a Discord hiccup, say) must not stop everyone after them.
                logger.warning("[members] could not update discord=" + link.discordId() + " (" + link.name() + "): " + e);
            }
        }

        for (Link link : toUndo) {
            // A Discord account that's still linked (to another Minecraft account) keeps its roles.
            if (linked.contains(link.discordId())) {
                continue;
            }
            try {
                unsyncMember(guild, link, role, originalRole, milestoneRoles);
            } catch (RuntimeException e) {
                logger.warning("[members] could not undo roles for discord=" + link.discordId() + ", will retry: " + e);
                pending.add(link);
            }
        }

        if (fullCheck && role != null) {
            sweepUnlinked(guild, role, linked);
        }
        if (fullCheck && originalRole != null) {
            sweepOriginals(guild, originalRole, originals);
        }
    }

    /** Takes the Original Supporter role from everyone holding it who isn't an Original Supporter on the sheet. */
    private void sweepOriginals(Guild guild, Role originalRole, Set<String> originals) {
        List<String> holders;
        try {
            holders = membersWithRole(guild.getId(), originalRole.getId());
        } catch (Exception e) {
            warnOnce("sweep-original", "couldn't list who has the Original Supporter role, so members removed from the sheet keep it: "
                    + e.getMessage() + ". Turn on Server Members Intent under Bot > Privileged Gateway Intents in the Developer Portal.");
            return;
        }
        for (String discordId : strays(holders, originals)) {
            Member member;
            try {
                member = fetch(guild, discordId);
            } catch (RuntimeException e) {
                logger.warning("[members] could not check discord=" + discordId + " for the Original Supporter role: " + e);
                continue;
            }
            if (member == null || member.isOwner() || isAdmin(member)) {
                continue;
            }
            act(() -> guild.removeRoleFromMember(member, originalRole).complete(),
                    "took Original Supporter from discord=" + discordId + " (not an Original Supporter on the sheet)");
        }
    }

    private boolean isAdmin(Member member) {
        String adminRole = settings.adminRoleId();
        return adminRole != null && !adminRole.isEmpty() && member.getRoles().stream().anyMatch(r -> r.getId().equals(adminRole));
    }

    /** Takes the linked role from everyone holding it who isn't linked. Needs the Server Members Intent. */
    private void sweepUnlinked(Guild guild, Role role, Set<String> linked) {
        List<String> holders;
        try {
            holders = membersWithRole(guild.getId(), role.getId());
        } catch (Exception e) {
            warnOnce("sweep", "couldn't list who has the linked role, so members who were unlinked before (or given it by hand) keep it: "
                    + e.getMessage() + ". Turn on Server Members Intent under Bot > Privileged Gateway Intents in the Developer Portal.");
            return;
        }
        for (String discordId : strays(holders, linked)) {
            act(() -> guild.removeRoleFromMember(net.dv8tion.jda.api.entities.UserSnowflake.fromId(discordId), role).complete(),
                    "took the linked role from discord=" + discordId + " (not linked)");
        }
    }

    /**
     * Who holds the Server Booster role. Lists the server in one go if the Server Members Intent is on, otherwise
     * looks up each linked member (only linked members can use the perks anyway).
     */
    java.util.Set<String> boosters(net.dv8tion.jda.api.JDA jda, String roleId, java.util.Set<String> linked) {
        Guild guild = jda.getGuildById(settings.guildId());
        if (guild == null) {
            return java.util.Set.of();
        }
        try {
            return new java.util.HashSet<>(membersWithRole(guild.getId(), roleId));
        } catch (Exception e) {
            java.util.Set<String> found = new java.util.HashSet<>();
            for (String id : linked) {
                Member member = fetch(guild, id);
                if (member != null && member.getRoles().stream().anyMatch(r -> r.getId().equals(roleId))) {
                    found.add(id);
                }
            }
            return found;
        }
    }

    /** Role holders who aren't linked, in the order Discord listed them. */
    static List<String> strays(List<String> holders, Set<String> linked) {
        return holders.stream().filter(id -> !linked.contains(id)).distinct().toList();
    }

    /** Lists the server's members over REST (1,000 at a time) and returns the IDs of those with the role. */
    private List<String> membersWithRole(String guildId, String roleId) throws Exception {
        List<String> found = new ArrayList<>();
        String after = "0";
        while (true) {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                            "https://discord.com/api/v10/guilds/" + guildId + "/members?limit=1000&after=" + after))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Authorization", "Bot " + settings.discordToken())
                    .GET().build();
            java.net.http.HttpResponse<String> response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new java.io.IOException("Discord answered HTTP " + response.statusCode());
            }
            com.google.gson.JsonArray page = com.google.gson.JsonParser.parseString(response.body()).getAsJsonArray();
            for (com.google.gson.JsonElement element : page) {
                com.google.gson.JsonObject member = element.getAsJsonObject();
                String id = member.getAsJsonObject("user").get("id").getAsString();
                for (com.google.gson.JsonElement memberRole : member.getAsJsonArray("roles")) {
                    if (memberRole.getAsString().equals(roleId)) {
                        found.add(id);
                        break;
                    }
                }
                after = id;
            }
            if (page.size() < 1000) {
                return found;
            }
        }
    }

    private void syncMember(Guild guild, Link link, Role role, Role originalRole, boolean original,
                            Map<String, Role> milestoneRoles, long playtimeSeconds) {
        String nickname = nickname(link);
        Set<String> wantedMilestones = milestoneRolesFor(settings.milestoneRoles(), settings.milestoneRolesHighestOnly(),
                playtimeSeconds);
        wantedMilestones.retainAll(milestoneRoles.keySet());
        String state = nickname + "|" + (role != null) + "|" + (originalRole == null ? "-" : original) + "|"
                + new TreeSet<>(wantedMilestones);
        if (state.equals(applied.get(link.discordId()))) {
            return;
        }
        Member member = fetch(guild, link.discordId());
        if (member != null) {
            if (role != null && !member.getRoles().contains(role)) {
                act(() -> guild.addRoleToMember(member, role).complete(),
                        "gave the linked role to discord=" + link.discordId() + " (" + link.name() + ")");
            }
            if (originalRole != null) {
                boolean has = member.getRoles().contains(originalRole);
                if (original && !has) {
                    act(() -> guild.addRoleToMember(member, originalRole).complete(),
                            "gave Original Supporter to discord=" + link.discordId() + " (" + link.name() + ")");
                } else if (!original && has && !member.isOwner() && !isAdmin(member)) {
                    act(() -> guild.removeRoleFromMember(member, originalRole).complete(),
                            "took Original Supporter from discord=" + link.discordId() + " (" + link.name() + ", new-members table)");
                }
            }
            for (Map.Entry<String, Role> entry : milestoneRoles.entrySet()) {
                Role milestoneRole = entry.getValue();
                boolean has = member.getRoles().contains(milestoneRole);
                boolean wanted = wantedMilestones.contains(entry.getKey());
                if (wanted && !has) {
                    act(() -> guild.addRoleToMember(member, milestoneRole).complete(),
                            "gave " + milestoneRole.getName() + " to discord=" + link.discordId() + " (" + link.name() + ")");
                } else if (!wanted && has && settings.milestoneRolesHighestOnly()) {
                    act(() -> guild.removeRoleFromMember(member, milestoneRole).complete(),
                            "took " + milestoneRole.getName() + " from discord=" + link.discordId()
                                    + " (they have a higher milestone)");
                }
            }
            if (settings.nicknameSync() && nickname != null && !nickname.equals(member.getNickname()) && renameable(guild, member)) {
                act(() -> member.modifyNickname(nickname).complete(),
                        "set nickname of discord=" + link.discordId() + " to " + nickname);
            }
        }
        applied.put(link.discordId(), state);
    }

    private void unsyncMember(Guild guild, Link link, Role role, Role originalRole, Map<String, Role> milestoneRoles) {
        applied.remove(link.discordId());
        Member member = fetch(guild, link.discordId());
        if (member == null) {
            return;
        }
        if (role != null && member.getRoles().contains(role)) {
            act(() -> guild.removeRoleFromMember(member, role).complete(),
                    "took the linked role from discord=" + link.discordId() + " (unlinked)");
        }
        if (originalRole != null && member.getRoles().contains(originalRole) && !member.isOwner() && !isAdmin(member)) {
            act(() -> guild.removeRoleFromMember(member, originalRole).complete(),
                    "took Original Supporter from discord=" + link.discordId() + " (removed from sheet)");
        }
        for (Role milestoneRole : milestoneRoles.values()) {
            if (member.getRoles().contains(milestoneRole)) {
                act(() -> guild.removeRoleFromMember(member, milestoneRole).complete(),
                        "took " + milestoneRole.getName() + " from discord=" + link.discordId() + " (removed from sheet)");
            }
        }
        // Only undo a nickname the plugin set; one the member chose themselves is left alone.
        String nickname = nickname(link);
        if (settings.nicknameSync() && nickname != null && nickname.equals(member.getNickname()) && renameable(guild, member)) {
            act(() -> member.modifyNickname(null).complete(),
                    "reset nickname of discord=" + link.discordId() + " (removed from sheet)");
        }
    }

    private String nickname(Link link) {
        if (link.name() == null) {
            return null;
        }
        String nickname = settings.nicknameFormat().replace("{ign}", link.name()).trim();
        return nickname.length() > 32 ? nickname.substring(0, 32) : nickname;
    }

    /** Discord never lets a bot rename the server owner or anyone whose top role is at or above its own. */
    private static boolean renameable(Guild guild, Member member) {
        return !member.isOwner() && guild.getSelfMember().canInteract(member);
    }

    private static Member fetch(Guild guild, String discordId) {
        try {
            return guild.retrieveMemberById(discordId).complete();
        } catch (ErrorResponseException e) {
            if (e.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER || e.getErrorResponse() == ErrorResponse.UNKNOWN_USER) {
                return null;
            }
            throw e;
        }
    }

    private void act(Runnable action, String description) {
        try {
            action.run();
            logger.info("[members] " + description);
        } catch (PermissionException e) {
            warnOnce(e.getPermission().name(), "the bot is missing " + e.getPermission().getName()
                    + " or its role is too low to change members (" + e.getMessage() + ")");
        } catch (ErrorResponseException e) {
            logger.warning("[members] could not " + description + ": " + e.getMeaning());
        }
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) {
            logger.warning("[members] " + message);
        }
    }
}
