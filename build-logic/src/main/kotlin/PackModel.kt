package eu.nordtal.season.build

import groovy.json.JsonSlurper
import java.io.File
import javax.imageio.ImageIO

/**
 * The resource pack as one tree of plain maps and lists, for the tree and the properties panel of Nordtal Designer.
 *
 * Every path is relative to the resource pack's directory. `resource-pack/README.md` documents the shape. [of] marks a
 * texture, a block and a font that is one of the [Drafts] it is given, and lists them with the pull requests that hold
 * an older version of one.
 */
object PackModel {
    fun of(
        allocation: GlyphAllocation,
        root: File,
        listed: List<Drafts.Draft> = emptyList(),
        reviews: List<Drafts.Review> = emptyList(),
    ): Map<String, Any?> {
        val drafts = Marks(listed.map { it.id }.toSet(), reviews.flatMap { review -> review.drafts.map { it to review.number } }.toMap())
        val assets = root.resolve("src/assets")
        return linkedMapOf(
            "fonts" to allocation.fonts.map { font(it, root, drafts) },
            "items" to items(assets, root, drafts),
            "overrides" to overrides(assets, root, drafts),
            "statuses" to GlyphAllocation.STATUSES.toList(),
            "scalings" to TextureMeta.SCALINGS,
            "languages" to Texts.work(root).keys.map(Texts::language).toSortedSet().toList(),
            "texts" to texts(root, listed, drafts),
            "drafts" to
                listed.map {
                    linkedMapOf(
                        "id" to it.id,
                        "label" to it.label,
                        "state" to it.state,
                        "files" to it.files,
                        "keys" to it.keys,
                        "review" to it.review,
                        "conflict" to
                            it.conflict?.let { conflict ->
                                linkedMapOf(
                                    "files" to conflict.files,
                                    "fields" to
                                        conflict.fields.map { field ->
                                            linkedMapOf(
                                                "name" to field.name,
                                                "clone" to field.clone,
                                                "main" to field.main,
                                            )
                                        },
                                )
                            },
                    )
                },
            "reviews" to
                reviews.map {
                    linkedMapOf(
                        "number" to it.number,
                        "url" to it.url,
                        "decision" to it.decision,
                        "checks" to it.checks,
                        "comments" to
                            it.comments.map { comment ->
                                linkedMapOf(
                                    "author" to comment.author,
                                    "body" to comment.body,
                                    "path" to comment.path,
                                    "url" to comment.url,
                                )
                            },
                    )
                },
        )
    }

    /**
     * Each key of the language files with its value in each language, and the id of the asset it belongs to or null. A
     * text is marked with the draft that it is part of, which is its asset's or its own.
     */
    private fun texts(
        root: File,
        listed: List<Drafts.Draft>,
        drafts: Marks,
    ): List<Map<String, Any?>> {
        val values = sortedMapOf<String, MutableMap<String, String>>()
        for ((path, texts) in Texts.work(root)) {
            texts.forEach { (key, value) -> values.getOrPut(key) { sortedMapOf() }[Texts.language(path)] = value }
        }
        val owner = Texts.owner(root)
        val draftOf = listed.flatMap { draft -> draft.keys.map { it to draft.id } }.toMap()
        return values.map { (key, languages) ->
            val id = owner(key)
            linkedMapOf(
                "key" to key,
                "values" to languages,
                "owner" to id.takeIf { Texts.ownKey(it) == null },
                "draft" to draftOf[key],
                "review" to drafts.reviews[id],
            )
        }
    }

    /** The ids of the [drafts], and of each change in review with the number of its pull request. */
    internal class Marks(
        val drafts: Set<String>,
        val reviews: Map<String, Int> = emptyMap(),
    ) {
        /** The marks of the entry [id]: `draft` where it is one and `review` where a pull request holds it. */
        fun of(id: String) = arrayOf<Pair<String, Any?>>("draft" to id.takeIf { it in drafts }, "review" to reviews[id])
    }

    private fun font(
        font: GlyphAllocation.Font,
        root: File,
        drafts: Marks,
    ): Map<String, Any?> =
        linkedMapOf(
            "font" to font.id,
            "rows" to font.rows,
            "constant" to font.constant,
            "about" to font.about,
            *drafts.of("font ${font.id}"),
            "blocks" to font.blocks.map { block(it, font.id, root, drafts) },
        )

    private fun block(
        block: GlyphAllocation.Block,
        font: String,
        root: File,
        drafts: Marks,
    ): Map<String, Any?> {
        val range = block.range?.let { "%X-%X".format(it.first, it.last) }
        val entry =
            linkedMapOf<String, Any?>(
                "kind" to if (block.texture == null) "block" else "sheet",
                "title" to block.title,
                "range" to range,
                "status" to block.status,
                "about" to block.about,
                *drafts.of(block.texture ?: Drafts.blockId(font, range ?: block.title)),
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
        drafts: Marks,
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
        drafts: Marks,
    ): Map<String, Any?> {
        val png = GlyphAdvances.texture(root.resolve("src/assets"), id)
        val aseprite = root.resolve("art").resolve(png.relativeTo(root.resolve("src")).path.removeSuffix(".png") + ".aseprite")
        val image = if (png.isFile) runCatching { ImageIO.read(png) }.getOrNull() else null
        return linkedMapOf(
            "id" to id,
            "png" to png.relativeTo(root).invariantSeparatorsPath,
            "aseprite" to aseprite.relativeTo(root).invariantSeparatorsPath,
            "asepriteExists" to aseprite.isFile,
            *drafts.of(id),
            "width" to image?.width,
            "height" to image?.height,
        )
    }

    /** The `.mcmeta` beside [png], its `animation` and the `frames` that the animation plays of [texture]. */
    internal fun animation(
        png: File,
        root: File,
        texture: Map<String, Any?>,
    ): Map<String, Any?> {
        val mcmeta = File(png.path + ".mcmeta").takeIf { it.isFile }
        val animation = (mcmeta?.let { json(it) } as? Map<*, *>)?.get("animation") as? Map<*, *>
        return linkedMapOf(
            "mcmeta" to mcmeta?.relativeTo(root)?.invariantSeparatorsPath,
            "animation" to animation,
            "frames" to
                animation?.let {
                    val width = texture["width"] as Int? ?: return@let null
                    TextureMeta.frames(width, texture["height"] as Int, it)
                },
        )
    }

    /** The item definitions under `assets/<namespace>/items`, one category per namespace. */
    private fun items(
        assets: File,
        root: File,
        drafts: Marks,
    ): List<Map<String, Any?>> =
        namespaces(assets).mapNotNull { namespace ->
            val folder = namespace.resolve("items")
            val entries =
                files(folder, "json").map { file ->
                    val model = (json(file) as? Map<*, *>)?.get("model") as? Map<*, *>
                    val path = file.relativeTo(root).invariantSeparatorsPath
                    linkedMapOf(
                        "id" to "${namespace.name}:${file.relativeTo(folder).invariantSeparatorsPath.removeSuffix(".json")}",
                        "file" to path,
                        // An item without a texture is a draft of its own file, as Drafts names it.
                        *drafts.of(path),
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
    internal fun itemTexture(
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
        drafts: Marks,
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
                            val meta = File(png.path + ".mcmeta").takeIf { it.isFile }?.let { json(it) as? Map<*, *> }
                            val texture = texture("minecraft:" + png.relativeTo(textures).invariantSeparatorsPath, root, drafts)
                            linkedMapOf<String, Any?>("texture" to texture) + animation(png, root, texture) +
                                linkedMapOf(
                                    "scaling" to (meta?.get("gui") as? Map<*, *>)?.get("scaling"),
                                    "guiSprite" to png.relativeTo(textures).invariantSeparatorsPath.startsWith("gui/sprites/"),
                                    // The block or item of the same name, which Nordtal Designer places or gives as a preview.
                                    "block" to "minecraft:${png.nameWithoutExtension}".takeIf { category == "block" },
                                    "item" to "minecraft:${png.nameWithoutExtension}".takeIf { category == "item" },
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
