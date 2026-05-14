package moe.tekuza.m9player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppUpdateManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun cleanupInstalledOrOldUpdateApksDeletesApksThatAreNotNewerThanCurrentVersion() {
        val updateDir = temporaryFolder.newFolder("update_apk")
        val oldApk = updateDir.file("9player-v1.6.0.apk")
        val currentApk = updateDir.file("9player-v1.6.1.apk")
        val nextApk = updateDir.file("9player-v1.6.2.apk")
        val unknownApk = updateDir.file("9player-update.apk")
        val note = updateDir.file("note.txt")

        cleanupInstalledOrOldUpdateApks(updateDir, currentVersion = "1.6.1")

        assertFalse(oldApk.exists())
        assertFalse(currentApk.exists())
        assertTrue(nextApk.exists())
        assertTrue(unknownApk.exists())
        assertTrue(note.exists())
    }

    @Test
    fun cleanupUpdateApksExceptDeletesOtherUpdateApksBeforeDownloadingNewOne() {
        val updateDir = temporaryFolder.newFolder("update_apk")
        val keepApk = updateDir.file("9player-v1.6.2.apk")
        val oldApk = updateDir.file("9player-v1.6.1.apk")
        val otherApk = updateDir.file("other.apk")
        val note = updateDir.file("note.txt")

        cleanupUpdateApksExcept(updateDir, keepApk)

        assertTrue(keepApk.exists())
        assertFalse(oldApk.exists())
        assertFalse(otherApk.exists())
        assertTrue(note.exists())
    }

    private fun java.io.File.file(name: String): java.io.File {
        return resolve(name).also { it.writeText("x") }
    }
}
