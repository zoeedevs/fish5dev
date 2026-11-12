// ==========================================================================
// fish5 — Fabric mod build for Minecraft 26.2.
// All version coordinates live in gradle.properties.
// ==========================================================================
plugins {
    kotlin("jvm") version "2.4.10"
    id("net.fabricmc.fabric-loom") version "1.16.1"
}

group = "com.fish5"

base {
    archivesName.set("fish5")
}

val mcVersion = property("minecraft_version") as String
val loaderVersion = property("loader_version") as String
val fabricApiVersion = property("fabric_api_version") as String
val fabricKotlinVersion = property("fabric_kotlin_version") as String
val lwjglVersion = property("lwjgl_version") as String   // must match the LWJGL that MC 26.2 ships (NanoVG natives)
val nettyVersion = property("netty_version") as String   // must match the netty MC 26.2 bundles
val javaVersion = (property("java_version") as String).toInt()
val mcDep = property("mc_dep") as String                  // fabric.mod.json depends.minecraft

version = mcVersion

java.toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))

repositories {
    mavenLocal()
    maven("https://maven.fabricmc.net/")
    mavenCentral()
}

dependencies {
    "minecraft"("com.mojang:minecraft:$mcVersion")
    // Deobfuscated 26.x jar: no mappings, and plain `implementation` (NOT modImplementation) so
    // nothing is remapped.
    "implementation"("net.fabricmc:fabric-loader:$loaderVersion")
    "implementation"("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // Kotlin language support (entrypoint adapter). Bundled Jar-in-Jar so it ships with the mod.
    val flk = "net.fabricmc:fabric-language-kotlin:$fabricKotlinVersion"
    "implementation"(flk)
    "include"(flk)

    // NanoSVG (lwjgl-nanovg) — rasterizes the click GUI's SVG icons (GGRenderer). MC already ships the LWJGL
    // core at the same version; we add the nanovg module + its natives and bundle them Jar-in-Jar
    // so the mod is self-contained.
    "implementation"("org.lwjgl:lwjgl-nanovg:$lwjglVersion")
    "include"("org.lwjgl:lwjgl-nanovg:$lwjglVersion")
    for (os in listOf("windows", "linux", "macos", "macos-arm64")) {
        "implementation"("org.lwjgl:lwjgl-nanovg:$lwjglVersion:natives-$os")
        "include"("org.lwjgl:lwjgl-nanovg:$lwjglVersion:natives-$os")
    }

    // Netty proxy support for the Proxy Manager. MC ships netty (incl. codec-http) but NOT codec-socks /
    // handler-proxy, so add them at MC's netty version and bundle them Jar-in-Jar.
    for (mod in listOf("netty-codec-socks", "netty-handler-proxy")) {
        "implementation"("io.netty:$mod:$nettyVersion")
        "include"("io.netty:$mod:$nettyVersion")
    }
}

loom {
    // NVGPIPRenderer reaches into blaze3d's GL backend to bind the framebuffer NanoVG draws into.
    accessWidenerPath.set(file("src/main/resources/fish5.accesswidener"))
}

kotlin {
    jvmToolchain(javaVersion)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(javaVersion)
}

java {
    withSourcesJar()
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft", mcDep)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version, "minecraft" to mcDep)
    }
}
