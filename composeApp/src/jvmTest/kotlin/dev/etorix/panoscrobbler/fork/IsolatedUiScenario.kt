package dev.etorix.panoscrobbler.fork

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal fun runIsolatedUiScenario(name: String, scenarioClass: Class<*>, timeoutSeconds: Long = 90) {
    val fixture = Files.createTempDirectory("pano-$name-ui-")
    val home = Files.createDirectories(fixture.resolve("home"))
    val temp = Files.createDirectories(fixture.resolve("tmp"))
    val log = fixture.resolve("scenario.log")
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    val child = ProcessBuilder(
        java,
        "-Djava.awt.headless=true",
        "-Duser.home=$home",
        "-Djava.io.tmpdir=$temp",
        "-Dskiko.renderApi=SOFTWARE",
        "-cp",
        testClasspath(scenarioClass),
        scenarioClass.name,
        fixture.resolve("data").toString(),
    ).redirectErrorStream(true).redirectOutput(log.toFile())
    child.environment()["XDG_CONFIG_HOME"] = fixture.resolve("config").toString()
    child.environment()["XDG_CACHE_HOME"] = fixture.resolve("cache").toString()
    child.environment()["XDG_DATA_HOME"] = fixture.resolve("share").toString()

    val process = child.start()
    val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
    }
    assertTrue(finished, "$name UI scenario timed out. Fixture: $fixture\n${Files.readString(log)}")
    assertEquals(0, process.exitValue(), "$name UI scenario failed. Fixture: $fixture\n${Files.readString(log)}")
}

private fun testClasspath(scenarioClass: Class<*>): String {
    // Gradle can expose the test runtime through URLClassLoader while java.class.path has only its worker jar.
    val classLoaderEntries = generateSequence(scenarioClass.classLoader) { it.parent }
        .filterIsInstance<URLClassLoader>()
        .flatMap { loader -> loader.getURLs().asSequence() }
        .filter { it.protocol == "file" }
        .map { Path.of(it.toURI()).toString() }
        .toList()
    return (classLoaderEntries + System.getProperty("java.class.path").split(File.pathSeparator))
        .distinct()
        .joinToString(File.pathSeparator)
}
