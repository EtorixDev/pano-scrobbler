package dev.etorix.panoscrobbler.fork

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.etorix.panoscrobbler.db.PanoDb
import dev.etorix.panoscrobbler.utils.DesktopStuff
import dev.etorix.panoscrobbler.utils.PlatformStuff
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.system.exitProcess

class ForkDatabaseMigrationTest {
    @Test
    fun version22KeepsQueuedScrobblesWhenAddingMetadataTags() {
        runIsolatedUiScenario("database-migration", ForkDatabaseMigrationScenario::class.java)
    }
}

/** Runs only in [ForkDatabaseMigrationTest]'s isolated child JVM. */
object ForkDatabaseMigrationScenario {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            runBlocking {
                val data = Path.of(args.single())
                DesktopStuff.parseCmdlineArgs(arrayOf("--data-dir", data.toString(), "--no-update-check"))
                assertEquals(data.toFile().canonicalFile, DesktopStuff.appDataRoot.canonicalFile)

                val schema = Json.parseToJsonElement(
                    Files.readString(Path.of("schemas", "dev.etorix.panoscrobbler.db.PanoDb", "22.json"))
                ).jsonObject.getValue("database").jsonObject
                val dbFile = PlatformStuff.filesDir.resolve("panodb.db")
                BundledSQLiteDriver().open(dbFile.absolutePath).use { connection ->
                    schema.getValue("entities").jsonArray.forEach { element ->
                        val entity = element.jsonObject
                        val table = entity.getValue("tableName").jsonPrimitive.content
                        connection.execSQL(
                            entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                        )
                        entity["indices"]?.jsonArray.orEmpty().forEach { index ->
                            connection.execSQL(
                                index.jsonObject.getValue("createSql").jsonPrimitive.content
                                    .replace("\${TABLE_NAME}", table)
                            )
                        }
                    }
                    schema.getValue("setupQueries").jsonArray.forEach {
                        connection.execSQL(it.jsonPrimitive.content)
                    }
                    connection.execSQL("PRAGMA user_version = 22")
                    connection.execSQL(
                        "INSERT INTO PendingScrobbles " +
                                "(event, services, lastFailedTimestamp, artist, track, timestamp) " +
                                "VALUES ('scrobble', 1, 1704000000000, 'Artist', 'Queued Before Upgrade', 1703999900000)"
                    )
                }

                val db = PanoDb.db
                val dao = db.getPendingScrobblesDao()
                val retained = dao.allScrobblesForSubmission(10).single()
                assertEquals("Queued Before Upgrade", retained.scrobbleData.track)
                assertEquals(1703999900000L, retained.scrobbleData.timestamp)
                assertEquals(emptyMap(), retained.scrobbleData.extraTags)

                val tags = mapOf("org.musicbrainz.MUSICBRAINZ_TRACKID" to "recording-one")
                dao.insertIgnore(
                    retained.copy(
                        _id = 0,
                        scrobbleData = retained.scrobbleData.copy(track = "Queued After Upgrade", extraTags = tags),
                    )
                )
                val added = dao.allScrobblesForSubmission(10).single { it.scrobbleData.track == "Queued After Upgrade" }
                assertEquals(tags, added.scrobbleData.extraTags)
                db.close()
            }
        } catch (error: Throwable) {
            error.printStackTrace()
            exitProcess(1)
        }
        exitProcess(0)
    }
}
