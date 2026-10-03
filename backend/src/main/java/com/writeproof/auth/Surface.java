package com.writeproof.auth;

/**
 * Which app a session belongs to: the public app, or the admin app on its own origin. The proxy in
 * front of the API tells them apart and says so in {@link #HEADER}: the admin host sets it, the
 * public host strips it (frontend/Caddyfile). A token is stamped with its surface as its audience,
 * and each surface's token works only there, so a token taken from one app is useless in the other.
 */
public enum Surface {
    APP("writeproof-app"),
    ADMIN("writeproof-admin");

    /** Set to {@code admin} by the proxy for requests that arrive on the admin host. */
    public static final String HEADER = "X-Writeproof-Surface";

    private final String audience;

    Surface(String audience) {
        this.audience = audience;
    }

    /** The token's {@code aud} claim. */
    public String audience() {
        return audience;
    }

    /** The authority every request authenticated with this surface's token has. */
    public String authority() {
        return "SURFACE_" + name();
    }

    /** The surface named by the proxy's header; anything but {@code admin} is the public app. */
    static Surface fromHeader(String value) {
        return "admin".equals(value) ? ADMIN : APP;
    }
}
