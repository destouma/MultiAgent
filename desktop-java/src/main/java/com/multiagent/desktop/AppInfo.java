package com.multiagent.desktop;

/**
 * Single source of truth for the app's display name and version string. Kept in sync with
 * {@code pom.xml}'s {@code <version>} and the {@code jpackage --app-version} used at
 * packaging time (see ARCHITECTURE.md §8) - there's no Maven resource filtering wired up, so
 * this constant is updated by hand alongside those two on each release.
 */
public final class AppInfo {

    public static final String NAME = "MultiAgent";
    public static final String VERSION = "1.3.0";

    private AppInfo() {
    }

    /** e.g. {@code "MultiAgent 1.3.0"} - for window titles and the Settings footer. */
    public static String nameWithVersion() {
        return NAME + " " + VERSION;
    }
}
