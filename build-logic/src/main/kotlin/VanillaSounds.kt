package eu.nordtal.season.build

import groovy.json.JsonSlurper
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Properties

/**
 * The sounds of the season's Minecraft version, which live in its asset index and not in the client jar. [index] is the
 * `id`, `url` and `sha1` of that index as `:bridge-mod` writes them from the version manifest, and [cache] the folder
 * that keeps the index and each object once downloaded, checked against its SHA-1.
 *
 * Like the client jar, the sounds are a starting point and a preview. They are never committed and never part of the pack.
 */
class VanillaSounds(
    private val index: File,
    private val cache: File,
    private val fetch: (String) -> ByteArray = ::download,
) {
    private val objects: Map<String, Map<*, *>> by lazy {
        val properties = Properties().also { properties -> index.inputStream().use { properties.load(it) } }
        val sha1 = properties.getProperty("sha1")
        val file = cache.resolve("indexes/${properties.getProperty("id")}-$sha1.json")
        val json = JsonSlurper().parse(stored(file, sha1) { properties.getProperty("url") }) as Map<*, *>
        (json["objects"] as Map<*, *>).entries.associate { (name, entry) -> name.toString() to entry as Map<*, *> }
    }

    /** Each sound event of `minecraft/sounds.json` by its id, such as `minecraft:block.anvil.land`, sorted by id. */
    fun events(): Map<String, Map<*, *>> {
        val json = JsonSlurper().parse(file("minecraft/sounds.json")) as Map<*, *>
        return json.entries.associate { (event, value) -> "minecraft:$event" to value as Map<*, *> }.toSortedMap()
    }

    /** Whether the asset index holds the sound [name], such as `minecraft:random/anvil_land`. */
    fun has(name: String): Boolean = path(name) in objects

    /** The `.ogg` file of the sound [name], downloaded once. */
    fun sound(name: String): File {
        val path = path(name)
        if (path !in objects) throw FieldRefusal("sound", "Minecraft has no sound $name.")
        return file(path)
    }

    private fun path(name: String): String {
        val namespace = name.substringBefore(':', "minecraft")
        return "$namespace/sounds/${name.substringAfter(':')}.ogg"
    }

    private fun file(path: String): File {
        val hash = objects[path]?.get("hash") as String? ?: throw IllegalStateException("The asset index has no $path.")
        return stored(cache.resolve("objects/${hash.take(2)}/$hash"), hash) { "$RESOURCES/${hash.take(2)}/$hash" }
    }

    /** [file], downloaded from [url] first where it is missing or differs from [sha1]. */
    private fun stored(
        file: File,
        sha1: String,
        url: () -> String,
    ): File {
        if (file.isFile && sha1(file.readBytes()) == sha1) return file
        val bytes = fetch(url())
        check(sha1(bytes) == sha1) { "${url()} did not match its SHA-1 $sha1, so it was not kept." }
        AtomicFiles.write(mapOf(file to bytes))
        return file
    }

    companion object {
        private const val RESOURCES = "https://resources.download.minecraft.net"
        private val client =
            HttpClient
                .newBuilder()
                .connectTimeout(
                    Duration.ofSeconds(10),
                ).followRedirects(HttpClient.Redirect.NORMAL)
                .build()

        private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun download(url: String): ByteArray {
            val response =
                try {
                    client.send(
                        HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(60)).build(),
                        HttpResponse.BodyHandlers.ofByteArray(),
                    )
                } catch (e: IOException) {
                    throw IllegalStateException("$url cannot be downloaded (${e.message}). Minecraft's sounds need a connection once.", e)
                }
            check(response.statusCode() == 200) { "$url answered ${response.statusCode()}." }
            return response.body()
        }
    }
}
