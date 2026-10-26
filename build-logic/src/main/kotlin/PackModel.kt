package eu.nordtal.season.build

import groovy.json.JsonSlurper
import java.io.File
import javax.imageio.ImageIO

/**
 * The resource pack as one tree of plain maps and lists, for the tree and the properties panel of Nordtal Designer.
 *
 * Every path is relative to the resource pack's directory. `resource-pack/README.md` documents the shape. A texture is a
 * draft when its PNG or its source is among [of]'s `drafts`, the files that differ from the last commit.
 */
object PackModel {
    fun of(
        allocation: GlyphAllocation,
        root: File,
        drafts: Set<String> = emptySet(),
    ): Map<String, Any?> {
        val assets = root.resolve("src/assets")
        return linkedMapOf(
            "fonts" to allocation.fonts.map { font(it, root, drafts) },
            "items" to items(assets, root, drafts),
            "overrides" to overrides(assets, root, drafts),
            "statuses" to GlyphAllocation.STATUSES.toList(),
            "scalings" to TextureMeta.SCALINGS,
        )
    }

    private fun font(
        font: GlyphAllocation.Font,
        root: File,
        drafts: Set<String>,
    ): Map<String, Any?> =
        linkedMapOf(
            "font" to font.id,
            "rows" to font.rows,
            "constant" to font.constant,
            "about" to font.about,
            "blocks" to font.blocks.map { block(it, root, drafts) },
        )

    private fun block(
        block: GlyphAllocation.Block,
        root: File,
        drafts: Set<String>,
    ): Map<String, Any?> {
        val entry =
            linkedMapOf<String, Any?>(
                "kind" to if (block.texture == null) "block" else "sheet",
                "title" to block.title,
                "range" to block.range?.let { "%X-%X".format(it.first, it.last) },
                "status" to block.status,
                "about" to block.about,
            )
        if (block.texture == null) {
            entry["glyphs"] = block.glyphs.map { glyph(it, block, root, drafts) }
            return entry
        }
        entry["height"] = block.height
        entry["ascent"] = block.ascent
        entry["texture"] = texture(block.texture, root, drafts)
        entry["cells"] =
            (block.chars?.let { chars(it) } ?: block.glyphs.mapIndexed { column, glyph -> cell(0, column, glyph, block) })
                .onEach {
                    it["height"] = block.height
                    it["ascent"] = block.ascent
                }
        return entry
    }

    private fun glyph(
        glyph: GlyphAllocation.Glyph,
        block: GlyphAllocation.Block,
        root: File,
        drafts: Set<String>,
    ): Map<String, Any?> =
        linkedMapOf(
            "code" to "%X".format(glyph.codePoint),
            "constant" to glyph.constant,
            "name" to glyph.name,
            "list" to glyph.list,
            "status" to glyph.status,
            "effectiveStatus" to (glyph.status ?: block.status),
            "height" to glyph.height,
            "ascent" to glyph.ascent,
            "about" to glyph.about,
            "texture" to texture(glyph.texture, root, drafts),
        )

    private fun cell(
        row: Int,
        column: Int,
        glyph: GlyphAllocation.Glyph,
        block: GlyphAllocation.Block,
    ): MutableMap<String, Any?> =
        linkedMapOf(
            "row" to row,
            "column" to column,
            "code" to "%X".format(glyph.codePoint),
            "constant" to glyph.constant,
            "list" to glyph.list,
            "status" to glyph.status,
            "effectiveStatus" to (glyph.status ?: block.status),
            "about" to glyph.about,
        )

    /** The cells of a sheet of ordinary characters. `\u0000` is no cell. */
    private fun chars(rows: List<String>): List<MutableMap<String, Any?>> =
        rows.flatMapIndexed { row, text ->
            text.codePoints().toArray().withIndex().filter { it.value != 0 }.map { (column, codePoint) ->
                linkedMapOf<String, Any?>("row" to row, "column" to column, "code" to "%X".format(codePoint))
            }
        }

    /** A texture by its id, such as `nordtal:badges/star.png`: its PNG, its `.aseprite` source under `art/` and its size. */
    internal fun texture(
        id: String,
        root: File,
        drafts: Set<String> = emptySet(),
    ): Map<String, Any?> {
        val png = GlyphAdvances.texture(root.resolve("src/assets"), id)
        val aseprite = root.resolve("art").resolve(png.relativeTo(root.resolve("src")).path.removeSuffix(".png") + ".aseprite")
        val image = if (png.isFile) runCatching { ImageIO.read(png) }.getOrNull() else null
        return linkedMapOf(
            "id" to id,
            "png" to png.relativeTo(root).invariantSeparatorsPath,
            "aseprite" to aseprite.relativeTo(root).invariantSeparatorsPath,
            "asepriteExists" to aseprite.isFile,
            "draft" to
                (png.relativeTo(root).invariantSeparatorsPath in drafts || aseprite.relativeTo(root).invariantSeparatorsPath in drafts),
            "width" to image?.width,
            "height" to image?.height,
        )
    }

    /** The item definitions under `assets/<namespace>/items`, one category per namespace. */
    private fun items(
        assets: File,
        root: File,
        drafts: Set<String>,
    ): List<Map<String, Any?>> =
        namespaces(assets).mapNotNull { namespace ->
            val folder = namespace.resolve("items")
            val entries =
                files(folder, "json").map { file ->
                    val model = (json(file) as? Map<*, *>)?.get("model") as? Map<*, *>
                    linkedMapOf(
                        "id" to "${namespace.name}:${file.relativeTo(folder).invariantSeparatorsPath.removeSuffix(".json")}",
                        "file" to file.relativeTo(root).invariantSeparatorsPath,
                        "model" to model?.get("type"),
                        "texture" to itemTexture(model, assets)?.let { texture(it, root, drafts) },
                    )
                }
            entries.takeIf { it.isNotEmpty() }?.let { linkedMapOf("category" to namespace.name, "entries" to it) }
        }

    /**
     * The texture that a `minecraft:model` item draws: `layer0` of its model in the pack, or the model's first texture.
     * It is null for any other item type, a model outside the pack, a model without a texture, or a texture that is no
     * identifier, so that no path of the model leads out of the pack.
     */
    private fun itemTexture(
        model: Map<*, *>?,
        assets: File,
    ): String? {
        if (model?.get("type") != "minecraft:model") return null
        val reference = model["model"] as? String ?: return null
        val file = assets.resolve(runCatching { Identifier.resource(reference, "models", ".json") }.getOrNull() ?: return null)
        val textures = (file.takeIf { it.isFile }?.let { json(it) } as? Map<*, *>)?.get("textures") as? Map<*, *>
        val id = (textures?.get("layer0") ?: textures?.values?.firstOrNull()) as? String ?: return null
        val texture = (if (':' in id) id else "minecraft:$id") + ".png"
        return texture.takeIf { Identifier.problem(it) == null }
    }

    /** The textures under `assets/minecraft/textures`, one category per folder. */
    private fun overrides(
        assets: File,
        root: File,
        drafts: Set<String>,
    ): List<Map<String, Any?>> {
        val textures = assets.resolve("minecraft/textures")
        return files(textures, "png")
            .groupBy { it.parentFile.relativeTo(textures).invariantSeparatorsPath }
            .toSortedMap()
            .map { (category, pngs) ->
                linkedMapOf(
                    "category" to category,
                    "entries" to
                        pngs.map { png ->
                            val mcmeta = File(png.path + ".mcmeta").takeIf { it.isFile }
                            val meta = mcmeta?.let { json(it) as? Map<*, *> }
                            val texture = texture("minecraft:" + png.relativeTo(textures).invariantSeparatorsPath, root, drafts)
                            val animation = meta?.get("animation") as? Map<*, *>
                            linkedMapOf(
                                "texture" to texture,
                                "mcmeta" to mcmeta?.relativeTo(root)?.invariantSeparatorsPath,
                                "animation" to animation,
                                "frames" to
                                    animation?.let {
                                        val width = texture["width"] as Int? ?: return@let null
                                        TextureMeta.frames(width, texture["height"] as Int, it)
                                    },
                                "scaling" to (meta?.get("gui") as? Map<*, *>)?.get("scaling"),
                                "guiSprite" to png.relativeTo(textures).invariantSeparatorsPath.startsWith("gui/sprites/"),
                            )
                        },
                )
            }
    }

    private fun namespaces(assets: File): List<File> =
        assets
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.name }

    private fun files(
        folder: File,
        extension: String,
    ): List<File> =
        folder
            .walkTopDown()
            .filter { it.isFile && it.extension == extension }
            .sortedBy { it.invariantSeparatorsPath }
            .toList()

    private fun json(file: File): Any? = runCatching { JsonSlurper().parse(file) }.getOrNull()
}
