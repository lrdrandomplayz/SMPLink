package io.github.lrdrandomplayz.smplink;

/** The server's name and tagline (server.name and server.tagline in config.yml), shown in the bot's messages. */
public final class Branding {

    private static volatile String name = "our SMP";
    private static volatile String tagline = "";

    private Branding() {
    }

    public static String name() {
        return name;
    }

    public static String tagline() {
        return tagline;
    }

    static void set(String serverName, String serverTagline) {
        name = serverName == null || serverName.isBlank() ? "our SMP" : serverName.trim();
        tagline = serverTagline == null ? "" : serverTagline.trim();
    }
}
