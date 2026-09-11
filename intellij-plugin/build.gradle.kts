plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.multiagent"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Bytecode target: 21 (same as desktop-java). The IntelliJ Platform build itself needs JDK 21+.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
kotlin {
    jvmToolchain(21)
}

dependencies {
    intellijPlatform {
        // Pinned to a concrete IC build so `runIde` and the compile SDK match. Bump deliberately.
        intellijIdeaCommunity("2024.2.5")
        instrumentationTools()
        // bundledPlugin("Git4Idea") // add when the plugin uses the Git4Idea API instead of GitService's shell-out
    }

    // ---- Forked core deps (see src/main/java, package com.multiagent.intellij.core.*) ----
    // This plugin does NOT depend on desktop-java. The core Java packages are a deliberate
    // copy (strategy B): the two projects are kept independent. See README.md.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.9")
    implementation("com.fasterxml.jackson.core:jackson-core:2.18.9")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.multiagent.intellij"
        name = "MultiAgent"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

// PersonaRegistry (the forked core) normally finds bundled personas as a real filesystem
// directory next to wherever its own class's code source resolves to - but IntelliJ's
// PluginClassLoader doesn't populate a CodeSource at all (verified empirically: it's null at
// runtime), and the JVM's cwd under `runIde` is the unpacked IDE distribution's own directory,
// unrelated to this plugin or the project. Neither of PersonaRegistry's filesystem lookups
// can ever succeed here, unlike desktop-java's Maven/jpackage layout or vscode-extension's
// bundled-extension layout. So: bundle personas/*.json as plain classpath resources instead
// (loaded via Class.getResourceAsStream, which - unlike CodeSource - IS reliably supported by
// PluginClassLoader) and seed PersonaRegistry's writable user directory from them once at
// startup - see MultiAgentService.seedBuiltInPersonas(). This also fixes buildPlugin's
// distribution zip for free, since processResources output flows into the plugin jar either way.
tasks.named<ProcessResources>("processResources") {
    from(rootDir.parentFile.resolve("personas")) {
        into("personas")
    }
}
