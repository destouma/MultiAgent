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

// PersonaRegistry (the forked core, unchanged from desktop-java) looks for a real
// `personas/` directory as a filesystem sibling of wherever its own class's code source
// resolves to - for this plugin that's `<sandbox>/plugins/multiagent-intellij/lib/`, next to
// its own jar, once loaded through the IntelliJ Platform's PluginClassLoader. desktop-java's
// Maven build and vscode-extension's esbuild both solve the exact same problem the same way
// (copy the repo-root personas/ into their own build output) - this is Gradle's version of
// that, for `runIde`'s sandbox specifically. buildPlugin's distribution zip needs the
// equivalent before a real release (tracked as a Phase 5 "Marketplace prep" item).
val personasSourceDir = rootDir.parentFile.resolve("personas")
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>("prepareSandbox") {
    // pluginDirectory is only known once this task itself is configuring its copy spec, so a
    // separate Copy task wired via a lazy flatMap Provider hits "before task has completed"
    // during configuration-cache serialization. A doLast{} closure is the simple fix, at the
    // cost of opting this one task out of the configuration cache.
    notCompatibleWithConfigurationCache("copies repo-root personas/ into the sandbox next to this plugin's jar")
    doLast {
        if (personasSourceDir.isDirectory) {
            val dest = pluginDirectory.get().asFile.resolve("lib/personas")
            dest.mkdirs()
            personasSourceDir.listFiles { f -> f.extension == "json" }?.forEach { it.copyTo(dest.resolve(it.name), overwrite = true) }
        }
    }
}
