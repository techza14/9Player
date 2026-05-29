package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MdictMountedResourceResolverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun findMountedMediaFileResolvesOnlyInsideRoot() {
        val root = temporaryFolder.newFolder("media")
        val images = root.resolve("images").also { it.mkdirs() }
        val cover = images.resolve("Cover.PNG").also { it.writeText("ok") }

        assertEquals(cover.canonicalFile, findMountedMediaFile(root, "images/cover.png")?.canonicalFile)
        assertEquals(cover.canonicalFile, findMountedMediaFile(root, "cover.png")?.canonicalFile)
    }

    @Test
    fun findMountedMediaFileRejectsTraversalAndAbsolutePaths() {
        val root = temporaryFolder.newFolder("media")
        val outside = temporaryFolder.newFile("outside.txt")
        root.resolve("safe.txt").writeText("inside")

        assertNull(findMountedMediaFile(root, "../outside.txt"))
        assertNull(findMountedMediaFile(root, "images/../../outside.txt"))
        assertNull(findMountedMediaFile(root, outside.absolutePath))
    }
}
