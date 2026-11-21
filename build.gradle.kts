plugins {
    // Loom ships as two plugins: `net.fabricmc.fabric-loom` for the unobfuscated releases (26.1 on)
    // and `net.fabricmc.fabric-loom-remap` for the obfuscated 1.21.x ones. Both are declared and the
    // right one is applied per Stonecutter node below (the same arrangement as the KUI project).
    id("net.fabricmc.fabric-loom") version "1.17.20" apply false
    id("net.fabricmc.fabric-loom-remap") version "1.17.20" apply false
}

// This script is shared by every version node (versions/<mc>/). Per-node values (Minecraft version,
// loader, dependency range) come from versions/<mc>/gradle.properties; the mod's identity from the
// root gradle.properties.
val minecraftVersion = property("minecraft_version") as String
val unobfuscated = !minecraftVersion.startsWith("1.")
val javaVersion = if (unobfuscated) 25 else 21

apply(plugin = if (unobfuscated) "net.fabricmc.fabric-loom" else "net.fabricmc.fabric-loom-remap")

version = "${property("mod_version")}+mc$minecraftVersion"
group = property("maven_group") as String

base {
    archivesName = property("archives_base_name") as String
}

repositories {
    mavenCentral()
    // Mod Menu's API, compiled against for the optional settings hook.
    maven("https://maven.terraformersmc.com/releases/") {
        content { includeGroup("com.terraformersmc") }
    }
}

// The Loom plugin is applied dynamically, so its configurations are addressed by name.
dependencies {
    "minecraft"("com.mojang:minecraft:$minecraftVersion")
    if (unobfuscated) {
        // No mappings at all: 26.x already carries Mojang's names.
        "implementation"("net.fabricmc:fabric-loader:${property("loader_version")}")
    } else {
        // Mojang's own names for 1.21.x too, so the shared source reads the same on every node.
        // `project.` matters: inside this block a bare `extensions` is the dependency handler's.
        val loom = project.extensions.getByType(net.fabricmc.loom.api.LoomGradleExtensionAPI::class.java)
        "mappings"(loom.officialMojangMappings())
        "modImplementation"("net.fabricmc:fabric-loader:${property("loader_version")}")
    }
    // Mod Menu's API, only to compile the settings hook against; never bundled or required.
    // 26.x: a Mojang-named Java 25 build. 1.21.x: an intermediary-named Java 21 one, remapped.
    val modMenu = if (unobfuscated) "com.terraformersmc:modmenu:20.0.1" else "com.terraformersmc:modmenu:17.0.0"
    add(if (unobfuscated) "compileOnly" else "modCompileOnly", modMenu) {
        (this as ExternalModuleDependency).isTransitive = false
    }
}

// Renames between Minecraft versions. The source is written in 26.3's names; these rewrite it for
// older nodes. Each is a word-bounded regex with an exact reverse, because Stonecutter runs a
// replacement backwards on the nodes where its condition is false: a bare string swap such as
// "render" -> "extractRenderState" would rewrite every word containing "render" on 26.x.
stonecutter {
    fun rename(condition: String, newName: String, oldName: String) {
        replacements.regex(eval(current.version, condition)) {
            replace("\\b$newName\\b", oldName)
            reverse("\\b$oldName\\b", newName)
        }
    }
    // 26.1 renamed GUI drawing from render* to extract* and GuiGraphics to GuiGraphicsExtractor.
    rename("<26.1", "GuiGraphicsExtractor", "GuiGraphics")
    rename("<26.1", "extractRenderState", "render")
    rename("<26.1", "extractContents", "renderContents")
    rename("<26.1", "extractDefaultLabel", "renderDefaultLabel")
    rename("<26.1", "extractIcons", "drawIcons")
    replacements.regex(eval(current.version, "<26.1")) {
        replace("\\bg\\.text\\(", "g.drawString(")
        reverse("\\bg\\.drawString\\(", "g.text(")
    }
    // 1.21.9 brought input event objects. Before it, the screen handlers take raw numbers: each
    // signature is swapped for the old one, which wraps the numbers in a stand-in event (see
    // src/mc1.21.8/.../compat) so the method bodies are the same on every version.
    fun swap(condition: String, newText: String, oldText: String) {
        replacements.regex(eval(current.version, condition)) {
            replace(Regex.escape(newText), Regex.escapeReplacement(oldText))
            reverse(Regex.escape(oldText), Regex.escapeReplacement(newText))
        }
    }
    val wrapMouse = "MouseButtonEvent event = new MouseButtonEvent(eventX, eventY, eventButton);"
    swap("<1.21.9", "net.minecraft.client.input.MouseButtonEvent", "cc.serverlist.featured.compat.MouseButtonEvent")
    swap("<1.21.9", "net.minecraft.client.input.KeyEvent", "cc.serverlist.featured.compat.KeyEvent")
    swap("<1.21.9", "public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {",
        "public boolean mouseClicked(double eventX, double eventY, int eventButton) { $wrapMouse boolean doubleClick = false;")
    swap("<1.21.9", "public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {",
        "public boolean mouseDragged(double eventX, double eventY, int eventButton, double dx, double dy) { $wrapMouse")
    swap("<1.21.9", "public boolean mouseReleased(MouseButtonEvent event) {",
        "public boolean mouseReleased(double eventX, double eventY, int eventButton) { $wrapMouse")
    swap("<1.21.9", "public boolean keyPressed(KeyEvent event) {",
        "public boolean keyPressed(int eventKey, int eventScancode, int eventModifiers) { KeyEvent event = new KeyEvent(eventKey, eventScancode, eventModifiers);")
    swap("<1.21.9", "super.mouseClicked(event, doubleClick)", "super.mouseClicked(event.x(), event.y(), event.button())")
    swap("<1.21.9", "super.mouseDragged(event, dx, dy)", "super.mouseDragged(event.x(), event.y(), event.button(), dx, dy)")
    swap("<1.21.9", "super.mouseReleased(event)", "super.mouseReleased(event.x(), event.y(), event.button())")
    swap("<1.21.9", "super.keyPressed(event)", "super.keyPressed(event.key(), event.scancode(), event.modifiers())")

    // 1.21.11 renamed ResourceLocation to Identifier and moved Util into net.minecraft.util.
    rename("<1.21.11", "Identifier", "ResourceLocation")
    replacements.regex(eval(current.version, "<1.21.11")) {
        replace("\\bnet\\.minecraft\\.util\\.Util\\b", "net.minecraft.Util")
        reverse("\\bnet\\.minecraft\\.Util\\b", "net.minecraft.util.Util")
    }
}

// Version-specific shims live in src/mc<compat_version>/java (compat_version defaults to the node).
sourceSets.named("main") {
    java.srcDir(rootProject.file("src/mc${findProperty("compat_version") ?: minecraftVersion}/java"))
}

val fabricModExpansions = mapOf(
    "version" to project.version.toString(),
    "minecraft" to property("minecraft_dep").toString(),
    "java" to javaVersion.toString(),
    "loader" to property("loader_dep").toString(),
)

tasks.named<ProcessResources>("processResources") {
    inputs.properties(fabricModExpansions)
    filesMatching("fabric.mod.json") { expand(fabricModExpansions) }
    filesMatching("serverlist.mixins.json") { expand(fabricModExpansions) }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaVersion
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "2000"))
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
}
