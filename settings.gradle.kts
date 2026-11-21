pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases")
    }
}

plugins {
    // One source tree, every Minecraft version: https://stonecutter.kikugie.dev
    id("dev.kikugie.stonecutter") version "0.8.4"
}

stonecutter {
    create(rootProject) {
        // 1.21.9 also serves 1.21.10 (a hotfix of it); 26.1.2 serves all of 26.1.x.
        versions("1.21.8", "1.21.9", "1.21.11", "26.1.2", "26.2", "26.3")
        // The source as checked in is the 26.3 form; the others are switched in by Stonecutter.
        vcsVersion = "26.3"
    }
}

rootProject.name = "serverlist-featured"
