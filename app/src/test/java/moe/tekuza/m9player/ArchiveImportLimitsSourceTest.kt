package moe.tekuza.m9player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArchiveImportLimitsSourceTest {
    @Test
    fun nativeHoshiImporterLimitsEntriesAndStreamsMediaWrites() {
        val source = File("src/main/cpp/hoshidicts/src/importer.cpp").readText()

        assertTrue(source.contains("kMaxZipEntries"))
        assertTrue(source.contains("kMaxMediaEntryBytes"))
        assertTrue(source.contains("kMaxTotalMediaBytes"))
        assertTrue(source.contains("is_safe_zip_entry_name"))
        assertTrue(source.contains("zip_entry_size(archive)"))
        assertTrue(source.contains("""read_file_by_name(archive, "index.json", kMaxIndexBytes)"""))
        assertTrue(source.contains("""read_file_by_name(archive, "styles.css", kMaxStyleBytes)"""))
        assertTrue(source.contains("blobs.write(media->blob.data()"))
        assertFalse(source.contains("std::vector<char> blobs_buf"))
    }

    @Test
    fun dictionaryImportAndMediaResponsesHaveExplicitByteLimits() {
        val store = File("src/main/java/moe/tekuza/m9player/DictionarySqlStore.kt").readText()
        val media = File("src/main/java/moe/tekuza/m9player/DictionaryMediaBytes.kt").readText()
        val jni = File("src/main/cpp/hoshidicts_jni.cpp").readText()

        assertTrue(store.contains("HOSHI_IMPORT_ARCHIVE_MAX_BYTES"))
        assertTrue(store.contains("HOSHI_TYPE_SCAN_MAX_ENTRIES"))
        assertTrue(store.contains("HOSHI_TYPE_SCAN_MAX_ENTRY_BYTES"))
        assertTrue(store.contains("HOSHI_META_TYPE_SCAN_MAX_BYTES"))
        assertTrue(media.contains("DICTIONARY_MEDIA_RESPONSE_MAX_BYTES"))
        assertTrue(media.contains("readDictionaryMediaBytesLimited"))
        assertFalse(media.contains("input.readBytes()"))
        assertTrue(jni.contains("kMaxJavaMediaBytes"))
        assertTrue(jni.contains("entry.size > kMaxJavaMediaBytes"))
    }
}
