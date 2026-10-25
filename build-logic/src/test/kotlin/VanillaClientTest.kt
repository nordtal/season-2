package eu.nordtal.season.build

import com.sun.net.httpserver.HttpServer
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class VanillaClientTest {
    @TempDir
    lateinit var directory: File

    private lateinit var server: HttpServer
    private val requests = mutableListOf<String>()
    private val responses = mutableMapOf<String, ByteArray>()
    private val jar =
        zip(
            "assets/minecraft/textures/block/stone.png" to png(16, 16),
            "assets/minecraft/textures/block/water_still.png" to png(16, 512),
            "assets/minecraft/textures/block/water_still.png.mcmeta" to """{"animation": {}}""".toByteArray(),
            "assets/minecraft/models/block/stone.json" to "{}".toByteArray(),
            "net/minecraft/Main.class" to ByteArray(4),
        )

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests += path
            val body = responses[path]
            exchange.sendResponseHeaders(if (body == null) 404 else 200, body?.size?.toLong() ?: -1)
            body?.let { exchange.responseBody.write(it) }
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun theJarIsDownloadedOnceAndCheckedAgainstTheManifest() {
        serve("26.2", jar, sha1(jar))
        val cache = directory.resolve("cache")

        val first = VanillaClient.jar("26.2", manifest(), cache)
        val second = VanillaClient.jar("26.2", manifest(), cache)

        assertArrayEquals(jar, first.readBytes())
        assertEquals(first, second)
        assertEquals(listOf("/manifest.json", "/26.2.json", "/client.jar"), requests)
    }

    @Test
    fun aJarWhoseSha1DiffersFromTheManifestIsRefusedAndNotKept() {
        serve("26.2", jar, sha1("another jar".toByteArray()))
        val cache = directory.resolve("cache")

        val refusal = assertThrows(IllegalStateException::class.java) { VanillaClient.jar("26.2", manifest(), cache) }

        assertTrue("SHA-1" in refusal.message!!, refusal.message)
        assertFalse(cache.walkTopDown().any { it.name == "client.jar" })
    }

    @Test
    fun aVersionTheManifestDoesNotListIsRefused() {
        serve("26.2", jar, sha1(jar))

        val refusal =
            assertThrows(IllegalStateException::class.java) { VanillaClient.jar("26.9", manifest(), directory.resolve("cache")) }

        assertTrue("26.9" in refusal.message!!, refusal.message)
    }

    @Test
    fun theTexturesAreListedWithTheirPixelsAndAnimation() {
        val file = directory.resolve("client.jar").apply { writeBytes(jar) }

        assertEquals(
            listOf(
                mapOf("id" to "minecraft:block/stone.png", "width" to 16, "height" to 16, "mcmeta" to false),
                mapOf("id" to "minecraft:block/water_still.png", "width" to 16, "height" to 512, "mcmeta" to true),
            ),
            VanillaClient.textures(file),
        )
    }

    @Test
    fun aTextureIsCopiedWithItsMcmetaToTheOverridePath() {
        val file = directory.resolve("client.jar").apply { writeBytes(jar) }
        val assets = directory.resolve("pack/src/assets")

        val copied = VanillaClient.copy(file, "minecraft:block/water_still.png", assets)

        val png = assets.resolve("minecraft/textures/block/water_still.png")
        assertEquals(listOf(png, File(png.path + ".mcmeta")), copied)
        assertEquals(512, ImageIO.read(png).height)
        assertEquals("""{"animation": {}}""", File(png.path + ".mcmeta").readText())
    }

    @Test
    fun aCopyRefusesATextureTheJarLacksAndAnExistingOverride() {
        val file = directory.resolve("client.jar").apply { writeBytes(jar) }
        val assets = directory.resolve("pack/src/assets")
        VanillaClient.copy(file, "minecraft:block/stone.png", assets)

        assertThrows(IllegalArgumentException::class.java) { VanillaClient.copy(file, "minecraft:block/nothing.png", assets) }
        assertThrows(IllegalArgumentException::class.java) { VanillaClient.copy(file, "minecraft:../../stone.png", assets) }
        assertThrows(IllegalArgumentException::class.java) { VanillaClient.copy(file, "minecraft:Block/stone.png", assets) }
        assertThrows(IllegalArgumentException::class.java) { VanillaClient.copy(file, "block/stone.png", assets) }
        assertThrows(IllegalStateException::class.java) { VanillaClient.copy(file, "minecraft:block/stone.png", assets) }
    }

    @Test
    fun theListingPrintsItsPathOnARunThatIsUpToDate() {
        serve("26.2", jar, sha1(jar))
        // The build-logic classes, which the build below loads as its own.
        val classes =
            System
                .getProperty("java.class.path")
                .split(File.pathSeparator)
                .filter { "build-logic" in it && "/build/" in it }
                .joinToString(", ") { "'" + it.replace("\\", "/") + "'" }
        val project = directory.resolve("project").apply { mkdirs() }
        project.resolve("settings.gradle").writeText("")
        project.resolve("build.gradle").writeText(
            """
            buildscript { dependencies { classpath files($classes) } }
            tasks.register('list', eu.nordtal.season.build.ListVanillaTextures) {
                version = '26.2'
                manifest = '${manifest()}'
                cache = file('cache')
                listing = layout.buildDirectory.file('listing.json')
            }
            """.trimIndent(),
        )
        val runner = GradleRunner.create().withProjectDir(project).withArguments("list", "--quiet")

        runner.build()
        val second = runner.build().output

        assertTrue(project.resolve("build/listing.json").absolutePath in second, second)
    }

    private fun serve(
        version: String,
        client: ByteArray,
        sha1: String,
    ) {
        val base = "http://127.0.0.1:${server.address.port}"
        val versionJson =
            """{"id": "$version", "downloads": {"client": {"sha1": "$sha1", "size": ${client.size}, "url": "$base/client.jar"}}}"""
                .toByteArray()
        responses["/manifest.json"] =
            """{"latest": {}, "versions": [{"id": "$version", "url": "$base/$version.json", "sha1": "${sha1(versionJson)}"}]}"""
                .toByteArray()
        responses["/$version.json"] = versionJson
        responses["/client.jar"] = client
    }

    private fun manifest() = URI("http://127.0.0.1:${server.address.port}/manifest.json")

    private fun png(
        width: Int,
        height: Int,
    ): ByteArray =
        ByteArrayOutputStream()
            .also {
                ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", it)
            }.toByteArray()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
