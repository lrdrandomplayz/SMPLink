package io.github.lrdrandomplayz.smplink.http;

/** A failure that maps to a specific HTTP status and a machine-readable error code for the bot. */
public final class ApiException extends Exception {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException notLinked(String discordId) {
        return new ApiException(404, "not_linked", "Discord ID " + discordId + " is not linked to a Minecraft account.");
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "bad_request", message);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
