package com.writeproof.admin;

/** MODERATOR handles reports and takedowns; ADMIN can do everything. */
public enum AdminRole {
    MODERATOR,
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
