package com.multiagent.desktop;

/**
 * Exists solely so a packaged (jpackage), non-modular jar can start at all. App itself
 * extends javafx.application.Application - the JVM refuses to launch an Application
 * subclass directly as the manifest/--main-class main class unless JavaFX is on the module
 * path, which a plain classpath app built from jpackage's "input directory" mode never is
 * ("Error: JavaFX runtime components are missing, and are required to run this
 * application" - confirmed live against the packaged app-image). mvn javafx:run doesn't
 * need this detour: that plugin sets up the module path itself, so it still points
 * directly at App.
 */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        App.main(args);
    }
}
