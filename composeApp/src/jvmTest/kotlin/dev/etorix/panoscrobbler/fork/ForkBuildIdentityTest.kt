package dev.etorix.panoscrobbler.fork

import dev.etorix.panoscrobbler.BuildKonfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ForkBuildIdentityTest {
    @Test
    fun compiledIdentityUsesForkNumberAndIntegratedUpstreamVersion() {
        val root = assertNotNull(
            generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .firstOrNull { Files.isRegularFile(it.resolve("version-fork.txt")) },
            "Run the test from the repository or one of its modules",
        )
        val upstream = Files.readString(root.resolve("version.txt")).trim().toInt()
        val fork = Files.readString(root.resolve("version-fork.txt")).trim().toInt()

        assertEquals("dev.etorix.panoscrobbler", BuildKonfig.APP_ID)
        assertEquals("Pano Scrobbler ETD", BuildKonfig.APP_NAME)
        assertEquals(fork, BuildKonfig.VER_CODE)
        assertEquals("${upstream / 100}.${upstream % 100}-etd.$fork", BuildKonfig.VER_NAME)
    }
}
