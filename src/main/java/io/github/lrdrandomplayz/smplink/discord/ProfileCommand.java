package io.github.lrdrandomplayz.smplink.discord;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.utils.FileUpload;
import io.github.lrdrandomplayz.smplink.db.LinkRepository.Link;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.Strike;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.Warning;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.WarningRecord;
import io.github.lrdrandomplayz.smplink.db.TradeRepository.Rep;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;
import io.github.lrdrandomplayz.smplink.service.PlayerService;
import io.github.lrdrandomplayz.smplink.service.PlayerService.Standing;
import io.github.lrdrandomplayz.smplink.service.PlayerService.Stats;

/**
 * /profile: one card per member, split by dividers, with a menu to switch between their stats, playtime
 * and strikes. Each page can have a banner, from plugins/SMPLink/banners/&lt;page&gt;.png (or .gif /
 * .jpg / .webp), shown at the bottom of the card above the menu.
 */
final class ProfileCommand {

    enum Page {
        // Each page's colour is its banner's title colour.
        STATS("stats", "Stats", Glyph.STATS, "Kills, mining, deaths and trade rep", 0xBD1716),
        PLAYTIME("playtime", "Playtime", Glyph.TIME, "Hours played and leaderboard rank", 0x1D2D43),
        STRIKES("strikes", "Strikes", Glyph.ALERT, "Strikes and activity-check warnings", 0xA96743);

        final String id;
        final String label;
        final Glyph emoji;
        final String description;
        final int color;

        Page(String id, String label, Glyph emoji, String description, int color) {
            this.color = color;
            this.id = id;
            this.label = label;
            this.emoji = emoji;
            this.description = description;
        }

        static Page fromId(String id) {
            for (Page page : values()) {
                if (page.id.equals(id)) {
                    return page;
                }
            }
            return STATS;
        }
    }

    /** A rendered page: the card and the files it shows (its banner). */
    record Rendered(Container card, List<FileUpload> files) {
    }

    // "profile:<viewer>:<target>": only the person who ran /profile can switch pages, as with /leaderboard.
    static final String MENU_PREFIX = "profile:";
    private static final List<String> BANNER_TYPES = List.of("png", "jpg", "jpeg", "gif", "webp");

    /** A banner ready to send: re-encoded as a smaller JPEG where possible, and reloaded only when the file changes. */
    private record Banner(Path file, FileTime modified, byte[] data, String name) {
    }

    private final PlayerService players;
    private final TrackingRepository tracking;
    private final TradeReviews reviews;
    private final Strikes strikes;
    private final Path bannerFolder;
    private final Map<Page, Banner> banners = new ConcurrentHashMap<>();

    ProfileCommand(PlayerService players, TrackingRepository tracking, TradeReviews reviews, Strikes strikes, Path bannerFolder) {
        this.players = players;
        this.tracking = tracking;
        this.reviews = reviews;
        this.strikes = strikes;
        this.bannerFolder = bannerFolder;
    }

    static SlashCommandData definition() {
        return Commands.slash("profile", "A linked player's stats, playtime and activity-check strikes")
                .addOption(OptionType.USER, "user", "Whose profile to show (defaults to you)", false);
    }

    /** Blocks on the database and the main thread, so run it on a worker. */
    Rendered render(String viewerId, User target, Page page) throws Exception {
        List<ContainerChildComponent> parts = new ArrayList<>();
        List<FileUpload> files = new ArrayList<>();
        int color = page.color;
        switch (page) {
            case STATS -> {
                parts.addAll(statsBody(target.getName(), players.stats(target.getId()), reviews.rep(target.getId()),
                        reviews.middlemanRep(target.getId())));
            }
            case PLAYTIME -> parts.addAll(playtimeBody(target.getName(), players.playtime(target.getId())));
            case STRIKES -> {
                StrikesPage strikesPage = strikesPage(target);
                parts.addAll(strikesPage.parts());
                color = strikesColor(strikesPage.active());
            }
        }
        FileUpload banner = banner(page);
        if (banner != null) {
            parts.add(MediaGallery.of(MediaGalleryItem.fromUrl("attachment://" + banner.getName())));
            files.add(banner);
        }
        parts.add(menu(viewerId, target.getId(), page));
        return new Rendered(Container.of(parts).withAccentColor(color), files);
    }

    static ActionRow menu(String viewerId, String targetId, Page current) {
        StringSelectMenu.Builder menu = StringSelectMenu.create(MENU_PREFIX + viewerId + ":" + targetId);
        for (Page page : Page.values()) {
            menu.addOptions(SelectOption.of(page.label, page.id)
                    .withDescription(page.description)
                    .withEmoji(page.emoji.emoji()));
        }
        return ActionRow.of(menu.setDefaultValues(current.id).build());
    }

    private static Separator divider() {
        return Separator.createDivider(Separator.Spacing.SMALL);
    }

    private static Section header(String skinKind, String name, String text) {
        return Section.of(Thumbnail.fromUrl(Embeds.skin(skinKind, name)), TextDisplay.of(text));
    }

    // --- Stats ----------------------------------------------------------------------------------------------

    static List<ContainerChildComponent> statsBody(String username, Stats stats, Rep rep, Rep middlemanRep) {
        String name = stats.name() == null ? "Unknown player" : stats.name();
        String status = stats.online()
                ? Glyph.ONLINE + " **Online** in `" + stats.world() + "`"
                : Glyph.OFFLINE + " **Offline** · last seen " + Embeds.timestamp(stats.lastSeen(), 'R');
        String trading = rep.vouches() == 0 && rep.scamReports() == 0 && rep.trades() == 0 ? "No trades yet"
                : "**" + signed(rep.score()) + "** rep" + (rep.score() < 0 ? " " + Glyph.WARN : "") + " · 👍 " + rep.vouches() + " · " + Glyph.REPORT + " "
                + rep.scamReports() + " · " + rep.trades() + (rep.trades() == 1 ? " trade" : " trades");
        return List.of(
                header("body", name, "## " + Glyph.STATS + " " + name + "\n" + status + "\nLinked to **" + username + "**"),
                divider(),
                TextDisplay.of("### " + Glyph.KILLS + " Combat\n" + Glyph.KILLS + " **" + Embeds.number(stats.mobKills()) + "** mob kills · " + Glyph.ASSASSIN + " **"
                        + Embeds.number(stats.playerKills()) + "** player kills · " + Glyph.DEATHS + " **" + Embeds.number(stats.deaths()) + "** deaths"),
                TextDisplay.of("### " + Glyph.WORLD + " Exploring\n" + Glyph.TIME + " **" + Embeds.number(stats.playtimeHours()) + " h** played · " + Glyph.MINING + " **"
                        + Embeds.number(stats.blocksMined()) + "** blocks mined · " + Glyph.DISTANCE + " **" + Embeds.number(stats.distanceWalkedKm())
                        + " km** walked"),
                divider(),
                TextDisplay.of("### " + Glyph.TRADE + " Trading\n" + trading + Embeds.middlemanRep(middlemanRep)),
                divider(),
                TextDisplay.of("-# " + Glyph.CALENDAR + " First joined " + Embeds.timestamp(stats.firstJoin(), 'D') + " · " + Glyph.WATCH + " Last seen "
                        + (stats.online() ? "right now" : Embeds.timestamp(stats.lastSeen(), 'R')) + " · UUID " + stats.uuid()));
    }

    private static String signed(int score) {
        return (score > 0 ? "+" : "") + score;
    }

    // --- Playtime -------------------------------------------------------------------------------------------

    static List<ContainerChildComponent> playtimeBody(String username, Standing playtime) {
        String name = playtime.name() == null ? "Unknown player" : playtime.name();
        String status = playtime.online()
                ? Glyph.ONLINE + " **Online**" + (playtime.onlineSince() == null ? "" : " since " + Embeds.timestamp(playtime.onlineSince(), 'R'))
                : Glyph.OFFLINE + " **Offline** · last seen " + Embeds.timestamp(playtime.lastSeen(), 'R');
        return List.of(
                header("avatar", name, "## " + Glyph.TIME + " " + name + "'s playtime\n" + status + "\nLinked to **" + username + "**"),
                divider(),
                TextDisplay.of("### " + Glyph.TROPHY + " All time\n**" + Embeds.formatPlaytime(playtime.playtimeSeconds()) + "** · rank **#"
                        + playtime.rank() + "** of " + playtime.totalRanked()),
                divider(),
                TextDisplay.of("### " + Glyph.CALENDAR + " Recently\nLast 7 days **" + Embeds.formatPlaytime(playtime.weekSeconds()) + "** · since "
                        + Embeds.timestamp(Embeds.windowStart(playtime.trackingStarted(), 7), 'd')
                        + "\nThis month **" + Embeds.formatPlaytime(playtime.monthSeconds()) + "** · since "
                        + Embeds.timestamp(Embeds.windowStart(playtime.trackingStarted(), 30), 'd')),
                divider(),
                TextDisplay.of("-# " + Glyph.CALENDAR + " First joined " + Embeds.timestamp(playtime.firstJoin(), 'D')));
    }

    // --- Strikes --------------------------------------------------------------------------------------------

    /** The Strikes page and how many active strikes it shows (its colour depends on that). */
    private record StrikesPage(List<ContainerChildComponent> parts, int active) {
    }

    private StrikesPage strikesPage(User target) throws Exception {
        // Only the link is needed here (name and whether they're an original member), not the playtime ranking.
        Link link = players.link(target.getId());
        String discordId = link.discordId();
        List<Strike> active = strikes.active(discordId);
        List<Strike> all = SyncBridge.await(tracking.strikesSince(discordId, 0), PlayerService.DB_TIMEOUT_MS);
        Optional<Warning> open = SyncBridge.await(tracking.pendingWarning(discordId), PlayerService.DB_TIMEOUT_MS);
        List<WarningRecord> history = SyncBridge.await(tracking.warningHistory(discordId), PlayerService.DB_TIMEOUT_MS);
        String exemptAs = link.original() ? "Original member" : strikes.exemptRoleName(target.getJDA(), discordId);
        return new StrikesPage(strikesBody(target.getName(), link.name(), active, all.size() - active.size(), open.orElse(null),
                history, strikes.limit(target.getJDA(), discordId), strikes.expireMs(), strikes.expiryText(), exemptAs, strikes.weighted(active)),
                strikes.weighted(active));
    }

    /** Brown-orange (the banner's colour) with no strikes, yellow with one, red with two or more. */
    static int strikesColor(int strikes) {
        return strikes == 0 ? Page.STRIKES.color : strikes == 1 ? Embeds.WARNING : Embeds.DANGER;
    }

    /** {@code exemptAs} names why the member isn't activity-checked ("Original member", "Donor"), or is null. */
    static List<ContainerChildComponent> strikesBody(String username, String name, List<Strike> active, int expired, Warning open,
                                                     List<WarningRecord> history, int limit, long expireMs, String expiryText,
                                                     String exemptAs) {
        return strikesBody(username, name, active, expired, open, history, limit, expireMs, expiryText, exemptAs, active.size());
    }

    /** As above, with the count shown ({@code count}: raid strikes weigh more than one). */
    static List<ContainerChildComponent> strikesBody(String username, String name, List<Strike> active, int expired, Warning open,
                                                     List<WarningRecord> history, int limit, long expireMs, String expiryText,
                                                     String exemptAs, int count) {
        List<ContainerChildComponent> parts = new ArrayList<>();
        parts.add(header("avatar", name, "## " + Glyph.ALERT + " " + name + "'s strikes\n**" + count + " of " + limit + "** strikes"
                + (active.isEmpty() ? " " + Glyph.YES : "") + "\nLinked to **" + username + "**"
                + (exemptAs == null ? "" : "\n" + Glyph.SHIELD + " " + exemptAs + ": not part of the weekly activity check")));
        if (!active.isEmpty()) {
            StringBuilder list = new StringBuilder("### " + Glyph.ALERT + " Active strikes");
            for (Strike strike : active) {
                list.append("\n").append(Strikes.describe(strike)).append("\n-# Given <t:").append(strike.givenAt() / 1000)
                        .append(":D> · expires <t:").append((strike.givenAt() + expireMs) / 1000).append(":R>");
            }
            parts.add(divider());
            parts.add(TextDisplay.of(list.toString()));
        }
        if (open != null) {
            parts.add(divider());
            parts.add(TextDisplay.of("### " + Glyph.WARN + " Open activity warning\nDeadline <t:" + open.deadline() / 1000 + ":F> (<t:"
                    + open.deadline() / 1000 + ":R>)\nCheck progress with /playtime."));
        }
        if (exemptAs == null || !history.isEmpty() || expired > 0) {
            long passed = history.stream().filter(r -> r.status().equals("passed")).count();
            parts.add(divider());
            parts.add(TextDisplay.of("### " + Glyph.BOOK + " History\n" + (history.isEmpty() ? "Never warned by the activity check."
                    : "Activity check: warned **" + history.size() + "** time(s), passed **" + passed + "**, last <t:"
                    + history.get(0).warnedAt() / 1000 + ":D>")
                    + (expired > 0 ? "\n" + expired + " expired strike(s)" : "")));
        }
        parts.add(divider());
        parts.add(TextDisplay.of("-# Strikes last " + expiryText + "; " + limit + " at once means permanent removal."));
        return parts;
    }

    // --- banners --------------------------------------------------------------------------------------------

    /**
     * banners/stats.png and so on; null when there's no banner for the page. PNG and JPEG banners are
     * re-encoded as JPEG once and kept in memory until the file changes, so each view uploads a few dozen
     * KB instead of reading and sending a large PNG.
     */
    private FileUpload banner(Page page) {
        if (bannerFolder == null) {
            return null;
        }
        for (String type : BANNER_TYPES) {
            Path file = bannerFolder.resolve(page.id + "." + type);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                FileTime modified = Files.getLastModifiedTime(file);
                Banner cached = banners.get(page);
                if (cached == null || !cached.file().equals(file) || !cached.modified().equals(modified)) {
                    cached = load(page, file, type, modified);
                    banners.put(page, cached);
                }
                return FileUpload.fromData(cached.data(), cached.name());
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    private static Banner load(Page page, Path file, String type, FileTime modified) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        if (type.equals("png") || type.equals("jpg") || type.equals("jpeg")) {
            byte[] jpeg = toJpeg(raw);
            if (jpeg != null && jpeg.length < raw.length) {
                return new Banner(file, modified, jpeg, "banner-" + page.id + ".jpg");
            }
        }
        // GIFs keep their animation and WebP is already small, so they're sent as they are.
        return new Banner(file, modified, raw, "banner-" + page.id + "." + type);
    }

    /** The image as a high-quality JPEG, or null if it can't be read. */
    static byte[] toJpeg(byte[] image) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(image));
        if (source == null) {
            return null;
        }
        // JPEG has no transparency, so draw onto an opaque image first.
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.createGraphics().drawImage(source, 0, 0, java.awt.Color.BLACK, null);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(0.88f);
            writer.write(null, new IIOImage(rgb, null, null), params);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
