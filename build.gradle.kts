import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    // To optionally create a shadow/fat jar that bundle up any non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
}

val timestampVersion = providers.gradleProperty("timestampVersion")
    .orElse("0.1.0-SNAPSHOT")

qupathExtension {
    name = "TimeStamp"
    group = "io.github.qupath"
    version = timestampVersion.get()
    description = "QuPath live event timestamp and transcript capture extension"
    automaticModule = "io.github.qupath.extension.timestamp"
}

dependencies {

    // Main dependencies for most QuPath extensions
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)

    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

}

tasks.processResources {
    from("scripts/live_whisper_demo.py") {
        into("qupath/ext/timestamp/scripts")
    }
}

tasks.test {
    useJUnitPlatform()
}

val qupathUserDirectory = providers.gradleProperty("qupathUserDir")
    .orElse(providers.environmentVariable("QUPATH_USER_DIR"))
    .orElse(providers.systemProperty("user.home").map { "$it/QuPath/v0.6" })

tasks.register("deployToQuPath") {
    group = "deployment"
    description = "Builds and installs the TimeStamp JAR in the local QuPath extensions directory."
    dependsOn(tasks.named("jar"))
    outputs.upToDateWhen { false }

    doLast {
        val jarTask = tasks.named<Jar>("jar").get()
        val sourceJar = jarTask.archiveFile.get().asFile
        val extensionsDirectory = file("${qupathUserDirectory.get()}/extensions")

        if (!extensionsDirectory.exists() && !extensionsDirectory.mkdirs()) {
            throw GradleException("Could not create QuPath extensions directory: $extensionsDirectory")
        }
        if (!extensionsDirectory.isDirectory) {
            throw GradleException("QuPath extensions path is not a directory: $extensionsDirectory")
        }

        extensionsDirectory.listFiles()
            ?.filter {
                it.isFile &&
                        it.name.startsWith("TimeStamp-") &&
                        it.extension.equals("jar", ignoreCase = true) &&
                        it.name != sourceJar.name
            }
            ?.forEach {
                logger.lifecycle("Removing older installed TimeStamp JAR: ${it.name}")
                if (!it.delete()) {
                    throw GradleException(
                        "Could not remove ${it.absolutePath}. Close QuPath and try again."
                    )
                }
            }

        val targetJar = extensionsDirectory.resolve(sourceJar.name)
        val stagedJar = extensionsDirectory.resolve(".${sourceJar.name}.${System.nanoTime()}.tmp")
        try {
            sourceJar.copyTo(stagedJar, overwrite = true)
            try {
                Files.move(
                    stagedJar.toPath(),
                    targetJar.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    stagedJar.toPath(),
                    targetJar.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (exception: Exception) {
            throw GradleException(
                "Could not replace ${targetJar.absolutePath}. Close QuPath and try again.",
                exception,
            )
        } finally {
            stagedJar.delete()
        }

        logger.lifecycle("Installed ${sourceJar.name} to ${extensionsDirectory.absolutePath}")
        logger.lifecycle("Restart QuPath to load the updated extension.")
    }
}
