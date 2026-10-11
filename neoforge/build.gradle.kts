import buildsrc.chVersions
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

plugins {
    id("createharmonics.neoforge-base")
    id("com.gradleup.shadow")
}

val v = chVersions
val commonProject = project(":common")

base.archivesName = "${v.modId}-neoforge-${v.minecraft}"

neoForge {
    version = v.neoForge
    validateAccessTransformers = true

    commonProject.file("src/main/resources/META-INF/accesstransformer.cfg").takeIf { it.exists() }?.let {
        accessTransformers.from(it)
    }

    runs {
        register("client") {
            client()
            jvmArguments.add("-XX:TieredStopAtLevel=1")
            // SoftSynth polyphony: export Gervill internals (mod module + unnamed).
            jvmArguments.add("--add-exports=java.desktop/com.sun.media.sound=ALL-UNNAMED,create_resonance")
            jvmArguments.add("--add-opens=java.desktop/com.sun.media.sound=ALL-UNNAMED,create_resonance")
            jvmArguments.add("--add-opens=java.base/java.lang=ALL-UNNAMED,create_resonance")
            jvmArguments.add("--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED,create_resonance")
        }
        register("clientDebug") {
            client()
            jvmArguments.add("-XX:TieredStopAtLevel=1")
            jvmArguments.add("-XX:+AllowEnhancedClassRedefinition")
            jvmArguments.add("--add-exports=java.desktop/com.sun.media.sound=ALL-UNNAMED,create_resonance")
            jvmArguments.add("--add-opens=java.desktop/com.sun.media.sound=ALL-UNNAMED,create_resonance")
            jvmArguments.add("--add-opens=java.base/java.lang=ALL-UNNAMED,create_resonance")
            jvmArguments.add("--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED,create_resonance")
        }
        register("data") {
            data()
            programArguments.addAll(
                "--mod",
                v.modId,
                "--all",
                "--output",
                project.file("src/generated/resources/").absolutePath,
                "--existing",
                project.file("src/main/resources/").absolutePath,
                "--existing",
                commonProject.file("src/main/resources/").absolutePath,
            )
        }
        register("server") { server() }

        register("gameTestServer") {
            type = "gameTestServer"
            systemProperty("neoforge.enabledGameTestNamespaces", v.modId)
        }
    }

    mods {
        create(v.modId) {
            sourceSet(sourceSets["main"])
        }
    }
}

sourceSets.main {
    resources.srcDirs("src/generated/resources", "templates")
    java.srcDir("src/generated/java")
    kotlin.srcDir("src/generated/kotlin")
}

dependencies {
    implementation("thedarkcolour:kotlinforforge-neoforge:${v.kotlinForNeoForge}")

    implementation("com.simibubi.create:create-${v.minecraft}:${v.create}:slim") { isTransitive = false }
    implementation("net.createmod.ponder:ponder-neoforge:${v.ponder}+mc${v.minecraft}")
    compileOnly("dev.engine-room.flywheel:flywheel-neoforge-api-${v.minecraft}:${v.flywheel}")
    runtimeOnly("dev.engine-room.flywheel:flywheel-neoforge-${v.minecraft}:${v.flywheel}")
    implementation("com.tterrag.registrate:Registrate:${v.registrate}")
    compileOnly("mezz.jei:jei-${v.jeiMc}-neoforge:${v.jei}")
    runtimeOnly("mezz.jei:jei-${v.jeiMc}-neoforge:${v.jei}")

    api("dev.ryanhcode.sable:sable-common-${v.minecraft}:${v.sable}") {
        exclude("foundry.veil")
        exclude("com.tterrag.registrate")
        exclude(group = "fuzs.forgeconfigapiport", module = "forgeconfigapiport-common-neoforgeapi")
    }
    runtimeOnly(files("libs/forgeconfigapiport-common-neoforgeapi-21.1.3.jar"))

    compileOnly(commonProject)
    shadow("org.tukaani:xz:1.11")
    compileOnly("org.tukaani:xz:1.11")
}

tasks.withType<JavaCompile>().configureEach { source(commonProject.sourceSets["main"].allSource) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    source(commonProject.sourceSets["main"].kotlin)
}

tasks.named<ProcessResources>("processResources") {
    inputs.property("modVersion", project.version.toString())
    from(commonProject.sourceSets["main"].resources)
    from(rootProject.file("LICENSE.MD")) { into("META-INF") }
    from(rootProject.file("NOTICE.MD")) { into("META-INF") }
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    filesMatching("META-INF/neoforge.mods.toml") { expand(project.properties) }
}

val mixinConfigs = "${v.modId}.mixins.json,create_resonance.common.mixins.json"

tasks.named<Jar>("jar") {
    manifest.attributes("MixinConfigs" to mixinConfigs)
}

tasks.named<ShadowJar>("shadowJar") {
    manifest.attributes("MixinConfigs" to mixinConfigs)
    configurations = listOf(project.configurations.getByName("shadow"))
    dependencies { include(dependency("org.tukaani:xz:1.11")) }
    relocate("org.tukaani.xz", "io.github.qwer854645.createresonance.libs.tukaani.xz")
    from(rootProject.file("LICENSE.MD")) { into("META-INF") }
    from(rootProject.file("NOTICE.MD")) { into("META-INF") }
    archiveClassifier = ""
}

tasks.named("build") { dependsOn("shadowJar") }

val localProperties =
    Properties().apply {
        rootProject
            .file("local.properties")
            .takeIf { it.exists() }
            ?.inputStream()
            ?.use { load(it) }
    }

val prodModsDir =
    localProperties.getProperty("prodModsDir")
        ?: providers.gradleProperty("prodModsDir").orNull
        ?: "build/deploy"

tasks.register<Copy>("deployToProd") {
    group = "build"
    dependsOn("build")
    from(tasks.named("shadowJar"))
    into(file(prodModsDir))
}

tasks.register<GradleBuild>("cleanAll") {
    group = "build"
    tasks = listOf(":common:clean", ":neoforge:clean")
}
