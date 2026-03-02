package com.lesslag.setup.model;

/** Lifecycle status of a setup session. */
public enum SessionStatus {
    DISCOVERY("Running server discovery..."),
    PROFILING("Awaiting profile/tier selection"),
    REVIEW("Reviewing recommendations"),
    CONFIRMED("Session confirmed, applying changes"),
    APPLIED("Changes applied successfully"),
    ABORTED("Session aborted by user"),
    FAILED("Session failed during apply"),
    ROLLED_BACK("Changes rolled back");

    private final String description;

    SessionStatus(String description) {
        this.description = description;
    }

    public String getDescription() { return description; }
}
