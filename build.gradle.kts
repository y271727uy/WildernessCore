import net.neoforged.moddevgradle.dsl.NeoForgeExtension
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    idea
    `java-library`
    `maven-publish`
    id("net.neoforged.moddev") version "2.0.147"
}

val modId = providers.gradleProperty("mod_id").get()
val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val neoVersion = providers.gradleProperty("neo_version").get()
val registrateVersion = providers.gradleProperty("registrate_version").get()

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("mod_group_id").get()

base {
    archivesName.set(modId)
}

tasks.named<Wrapper>("wrapper") {
    distributionType = Wrapper.DistributionType.BIN
}

sourceSets.named("main") {
    resources.srcDir("src/generated/resources")
    resources.exclude("**/*.bbmodel")
    resources.exclude("src/generated/**/.cache")
}

repositories {
    mavenLocal()
    mavenCentral()

    exclusiveContent {
        forRepository {
            maven {
                name = "Registrate"
                url = uri("https://maven.gegy.dev/releases")
            }
        }
        filter {
            includeGroup("com.tterrag.registrate")
        }
    }

    // `lib` is only a lookup repository: nothing in it becomes a dependency
    // automatically. Register individual files from `lib` in dependencies.
    flatDir {
        dirs("lib")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

// Every JAR in `jarjar` is embedded into the final mod JAR by the built-in
// Jar-in-Jar task. The directory is intentionally not exposed on dev runtime.
val jarJarFiles = fileTree(layout.projectDirectory.dir("jarjar")) {
    include("*.jar")
}

// Jar-in-Jar requires every local file dependency to expose a unique module
// identity. Generate build-only copies with a stable identity so ordinary mod
// JARs (which often have no manifest module name) work without editing them.
val preparedJarJarTasks = jarJarFiles.files.map { source ->
    val suffix = Integer.toHexString(source.absolutePath.hashCode())
    tasks.register<Jar>("prepareJarJar${suffix}") {
        archiveFileName.set(source.name)
        destinationDirectory.set(layout.buildDirectory.dir("generated/jarjar-inputs"))
        from(zipTree(source))
        manifest.attributes["Automatic-Module-Name"] =
            "wildernesscore.jarjar.${source.nameWithoutExtension.replace(Regex("[^A-Za-z0-9_]"), "_")}"
    }
}

// Every JAR in `mod` is loaded only by the client development run. It is not
// a published dependency and is never copied into the output JAR.
val hotPlugModFiles = fileTree(layout.projectDirectory.dir("mod")) {
    include("*.jar")
}

dependencies {


    api("com.tterrag.registrate:Registrate:$registrateVersion")
    add("jarJar", "com.tterrag.registrate:Registrate:$registrateVersion")
    add("jarJar", files(preparedJarJarTasks.map { it.flatMap(Jar::getArchiveFile) }))


    implementation(files("lib/Modern-Industrialization-2.5.8.jar"))
    implementation(files("lib/guideme-21.1.19.jar"))

}


tasks.named("jarJar") {
    dependsOn(preparedJarJarTasks)
}

extensions.configure<NeoForgeExtension> {
    version = neoVersion

    parchment {
        mappingsVersion = providers.gradleProperty("parchment_mappings_version").get()
        minecraftVersion = providers.gradleProperty("parchment_minecraft_version").get()
    }

    runs {
        register("client") {
            client()
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
            hotPlugModFiles.files.forEach { file ->
                additionalRuntimeClasspathConfiguration.dependencies.add(
                    project.dependencies.create(project.files(file))
                )
            }
        }

        register("server") {
            server()
            programArgument("--nogui")
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
        }

        register("gameTestServer") {
            type = "gameTestServer"
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
        }

        register("data") {
            data()
            programArguments.addAll(
                "--mod", modId,
                "--all",
                "--output", file("src/generated/resources").absolutePath,
                "--existing", file("src/main/resources").absolutePath
            )
        }

        configureEach {
            systemProperty("forge.logging.markers", "REGISTRIES")
            logLevel = org.slf4j.event.Level.DEBUG
        }
    }

    mods {
        create(modId) {
            sourceSet(sourceSets.main.get())
        }
    }
}

// Keep the template's optional localRuntime convention available for manual
// registrations in `dependencies`; it does not scan `lib` by itself.
val localRuntime = configurations.maybeCreate("localRuntime")
configurations.named("runtimeClasspath") {
    extendsFrom(localRuntime)
}

val generateModMetadata = tasks.register<ProcessResources>("generateModMetadata") {
    val replaceProperties = mapOf(
        "minecraft_version" to minecraftVersion,
        "minecraft_version_range" to providers.gradleProperty("minecraft_version_range").get(),
        "neo_version" to neoVersion,
        "loader_version_range" to providers.gradleProperty("loader_version_range").get(),
        "mod_id" to modId,
        "mod_name" to providers.gradleProperty("mod_name").get(),
        "mod_license" to providers.gradleProperty("mod_license").get(),
        "mod_version" to providers.gradleProperty("mod_version").get()
    )

    inputs.properties(replaceProperties)
    expand(replaceProperties)
    from("src/main/templates")
    into(layout.buildDirectory.dir("generated/sources/modMetadata"))
}

sourceSets.named("main") {
    resources.srcDir(generateModMetadata)
}

extensions.configure<NeoForgeExtension> {
    ideSyncTask(generateModMetadata)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            url = uri(layout.projectDirectory.dir("repo"))
        }
    }
}

idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}
