package io.github.lrdrandomplayz.smplink.discord;

/**
 * The member list without a Google Sheet (linking.mode: codes): /whitelist, accepted applications and
 * strike removals whitelist and unlink players directly. Each method returns null when it worked, or what
 * went wrong in a few words.
 */
public interface Roster {

    /** Runs whitelist.add-command for this Minecraft name. */
    String whitelist(String ign);

    /** Removes the player from the whitelist and unlinks them. Either argument may be null. */
    Removed remove(String discordId, String ign);

    /** {@code ign} is the name that was removed (null if none was known); {@code problem} is null when it all worked. */
    record Removed(String ign, boolean unlinked, String problem) {
    }
}
