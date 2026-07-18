package moe.tekuza.m9player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArchiveImportLimitsSourceTest {
    @Test
    fun nativeHoshiImporterLimitsEntriesAndStreamsMediaWrites() {
        val source = File("src/main/cpp/hoshidicts/src/importer.cpp").readText()
        val zipSource = File("src/main/cpp/hoshidicts/src/zip/zip.cpp").readText()
        val zipHeader = File("src/main/cpp/hoshidicts/src/zip/zip.hpp").readText()

        assertTrue(zipHeader.contains("kMaxZipEntries = 20000"))
        assertTrue(zipHeader.contains("kMaxBankBytes = 64u * 1024u * 1024u"))
        assertTrue(zipHeader.contains("kMaxZipPathBytes = 512"))
        assertTrue(zipHeader.contains("kMaxMediaEntryBytes = 32u * 1024u * 1024u"))
        assertTrue(zipHeader.contains("kMaxTotalMediaBytes = 256ull * 1024ull * 1024ull"))
        assertTrue(zipSource.contains("is_safe_zip_entry_name"))
        assertTrue(zipSource.contains("e.compressed_size > file.size - e.data_offset"))
        assertTrue(source.contains("zip.read(index_idx, Zip::kMaxIndexBytes)"))
        assertTrue(source.contains("zip.read(styles_idx, Zip::kMaxStyleBytes)"))
        assertTrue(source.contains("media.write(buf.data()"))
        assertFalse(source.contains("std::vector<char> blobs_buf"))
    }

    @Test
    fun dictionaryImportAndMediaResponsesHaveExplicitByteLimits() {
        val store = File("src/main/java/moe/tekuza/m9player/DictionarySqlStore.kt").readText()
        val media = File("src/main/java/moe/tekuza/m9player/DictionaryMediaBytes.kt").readText()
        val jni = File("src/main/cpp/hoshidicts_jni.cpp").readText()

        assertTrue(store.contains("HOSHI_IMPORT_ARCHIVE_MAX_BYTES"))
        assertTrue(media.contains("DICTIONARY_MEDIA_RESPONSE_MAX_BYTES"))
        assertTrue(media.contains("readDictionaryMediaBytesLimited"))
        assertFalse(media.contains("input.readBytes()"))
        assertTrue(jni.contains("kMaxJavaMediaBytes"))
        assertTrue(jni.contains("entry.size > kMaxJavaMediaBytes"))
        assertTrue(jni.contains("obj->query.get_media_file(dict_name_str, media_path_str)"))
        assertTrue(jni.indexOf("obj->query.get_media_file(dict_name_str, media_path_str)") < jni.indexOf("get_imported_media_file(obj, root, media_path_str)"))
        assertTrue(jni.contains("read_media_index(root_path)"))
    }
}
