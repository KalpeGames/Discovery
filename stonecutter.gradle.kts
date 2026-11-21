plugins {
    id("dev.kikugie.stonecutter")
}

// Stonecutter controller. `active` is the version the IDE and the plain tasks target; Stonecutter
// rewrites it as you switch, so leave the marker comment in place.
//
//   ./gradlew chiseledBuild      every version; jars land in versions/<mc>/build/libs
//   ./gradlew releaseJars        the same, copied to build/release as serverlist-discovery-*.jar
stonecutter active "26.3" /* [SC] DO NOT EDIT */

tasks.register<Copy>("releaseJars") {
    group = "discovery"
    description = "Builds every Minecraft target and gathers the jars in build/release."
    dependsOn(subprojects.map { "${it.path}:build" })
    from(subprojects.map { it.layout.buildDirectory.dir("libs") }) {
        include("serverlist-featured-*.jar")
        exclude("*-sources.jar")
        rename("serverlist-featured-(.*)", "serverlist-discovery-$1")
    }
    into(layout.buildDirectory.dir("release"))
}
