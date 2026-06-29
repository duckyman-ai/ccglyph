// IntelliJ Platform Gradle Plugin 2.x — the current standard for building IntelliJ plugins
// (the old Gradle IntelliJ Plugin 1.x is deprecated and warned it couldn't build against 2024.2+).
// Migrated from 1.17.4 → 2.16.0. Requires Gradle 9.0.0+ / Java 17+.
// Migration guide: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-migration.html
plugins {
    id("org.jetbrains.intellij.platform") version "2.16.0"
    // Kotlin 2.4.0 — must be >=2.1 because pty4j 0.13.12 is compiled with Kotlin 2.1.21
    // (older compilers can't read its metadata)
    kotlin("jvm") version "2.4.0"
    // kotlinx-serialization — used for the generic JsonElement DOM: parsing/merging a profile's
    // settings.json and building the injected statusLine + hooks fragment. Runtime-only API
    // (no @Serializable classes), but the compiler plugin is applied so the runtime initialises.
    kotlin("plugin.serialization") version "2.4.0"
}

group = "com.workspect.plugin.ccglyph"
version = "1.0.0"

repositories {
    mavenCentral()
    // IntelliJ Platform releases + bundled/third-party plugin repositories
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // Build against IntelliJ IDEA Ultimate 2025.3 (JCEF is bundled with IU; native split API present).
    intellijPlatform {
        create("IU", "2025.3")
        // Access the IDE's built-in terminal settings (TerminalProjectOptionsProvider) so CCGlyph
        // can default to the same shell the user configured for the built-in terminal.
        bundledPlugin("org.jetbrains.plugins.terminal")
    }
    // pty4j 0.13.12 — native libs (win/linux/macOS) extracted to separate files at runtime.
    // JNA is excluded: use the IDE's bundled copy (shipping our own conflicts and causes
    // UnsatisfiedLinkError: Unable to locate JNA native support library).
    implementation("org.jetbrains.pty4j:pty4j:0.13.12") {
        exclude("net.java.dev.jna", "jna")
        exclude("net.java.dev.jna", "jna-platform")
    }
    // JSON DOM: parse + deep-merge a profile's settings.json with the injected statusLine/hooks,
    // and serialise the runtime settings.json handed to `claude --settings`.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

intellijPlatform {
    pluginConfiguration {
        // The plugin's compatibility range. sinceBuild=253 → 2025.3+ (the native split API + build target).
        // untilBuild left open (no upper bound) → installs on newer IDEs too.
        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }
    // v1.0 is desktop-only. Split Mode (Remote Development / Gateway / dev-containers) is intentionally
    // NOT enabled: the terminal UI (xterm.js in JCEF = frontend) and the PTY/shell (pty4j = backend)
    // would need separating into modules first.
    // Path forward: https://plugins.jetbrains.com/docs/intellij/configuring-split-mode.html
}

// Force JDK 17 for both Kotlin+Java (plugin bytecode target 17 runs on JBR 21 too; kept low for broad compat)
kotlin {
    jvmToolchain(17)
}

tasks {
    // Sandbox IDE needs more memory + the JCEF variant of the JBR.
    runIde {
        jvmArgs("-Xmx2g")
    }
}