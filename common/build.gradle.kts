import buildsrc.chVersions

plugins {
    id("createharmonics.neoforge-base")
}

val v = chVersions

base.archivesName = "${v.modId}-common-${v.minecraft}"

neoForge {
    version = v.neoForge

    file("src/main/resources/META-INF/accesstransformer.cfg").takeIf { it.exists() }?.let {
        accessTransformers.from(it)
    }
}

dependencies {
    testImplementation(kotlin("stdlib"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")

    compileOnly("org.tukaani:xz:1.11")
    compileOnly("org.spongepowered:mixin:0.8.5")

    compileOnly("com.simibubi.create:create-${v.minecraft}:${v.create}:slim") { isTransitive = false }
    compileOnly("net.createmod.ponder:ponder-neoforge:${v.ponder}+mc${v.minecraft}")
    compileOnly("dev.engine-room.flywheel:flywheel-neoforge-api-${v.minecraft}:${v.flywheel}")
    compileOnly("com.tterrag.registrate:Registrate:${v.registrate}")
    compileOnly("mezz.jei:jei-${v.jeiMc}-common-api:${v.jei}")

    api("dev.ryanhcode.sable:sable-common-${v.minecraft}:${v.sable}") {
        exclude("foundry.veil")
        exclude("com.tterrag.registrate")
        exclude(group = "fuzs.forgeconfigapiport", module = "forgeconfigapiport-common-neoforgeapi")
    }
}

// Playback regressions run without a Minecraft client or an audio output device.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") { enabled = true }
tasks.named<Test>("test") { useJUnitPlatform() }

configurations.create("commonJava") {
    isCanBeResolved = false
    isCanBeConsumed = true
}
configurations.create("commonResources") {
    isCanBeResolved = false
    isCanBeConsumed = true
}
artifacts {
    add("commonJava", sourceSets["main"].java.sourceDirectories.singleFile)
    add("commonResources", sourceSets["main"].resources.sourceDirectories.singleFile)
}
