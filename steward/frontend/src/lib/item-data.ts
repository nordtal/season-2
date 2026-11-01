import type { GameData } from "@/lib/api"
import { gameKey, nameOfId } from "@/lib/references"
import { t } from "@/lib/texts"

/**
 * Reads the whole item an admin took in from the game: Base64 of the gzipped NBT that Paper serialises a stack to.
 */

/** One NBT value. A long is a bigint; the numeric arrays are plain lists. */
export type Nbt = number | bigint | string | Nbt[] | { [key: string]: Nbt }

type Compound = { [key: string]: Nbt }

/** A stored item: its material and its data components, keyed by their namespaced id. */
export type StoredItem = { id: string; components: Compound }

/** A line of an item's tooltip; `lore` marks a line of its lore. */
export type ItemLine = { text: string; lore?: boolean }

/** What Steward shows of an item: its rendered name, each enchantment with its level, and the lines of its tooltip. */
export type ItemDetails = { name?: string; enchantments: string[]; lines: ItemLine[] }

const MINECRAFT = "minecraft:"

/** Components that the name, the badges or a line of their own show, or that only a plugin reads. */
const SHOWN_ELSEWHERE = new Set(
  [
    "custom_name",
    "item_name",
    "lore",
    "enchantments",
    "stored_enchantments",
    "attribute_modifiers",
    "unbreakable",
    "damage",
    "custom_model_data",
    "item_model",
    "dyed_color",
    "custom_data",
    "tooltip_display",
  ].map((name) => MINECRAFT + name),
)

const ROMAN = ["I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"]

/** Answers the item that `data` holds, or throws when it is not an item Paper serialised. */
export async function readItemData(data: string): Promise<StoredItem> {
  const packed = Uint8Array.from(atob(data.trim()), (char) => char.charCodeAt(0))
  const unpacked = await gunzip(packed).catch((cause: unknown) => {
    throw new Error("the item data is not gzipped", { cause })
  })
  const root = new NbtReader(unpacked).root()
  const id = root.id
  if (typeof id !== "string") throw new Error("the item data names no item")
  const components = root.components
  return { id, components: isCompound(components) ? components : {} }
}

async function gunzip(bytes: Uint8Array<ArrayBuffer>): Promise<Uint8Array> {
  const unpacked = new Response(bytes).body?.pipeThrough(new DecompressionStream("gzip"))
  return new Uint8Array(await new Response(unpacked).arrayBuffer())
}

/** Reads Java's NBT: big-endian, strings in modified UTF-8, the root a named compound. */
class NbtReader {
  private at = 0
  private readonly bytes: Uint8Array
  private readonly view: DataView

  constructor(bytes: Uint8Array) {
    this.bytes = bytes
    this.view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  }

  root(): Compound {
    if (this.byte() !== 10) throw new Error("the item data is no compound")
    this.string()
    return this.compound()
  }

  private value(type: number): Nbt {
    switch (type) {
      case 1:
        return this.byte(true)
      case 2:
        return this.step(2, () => this.view.getInt16(this.at))
      case 3:
        return this.int()
      case 4:
        return this.step(8, () => this.view.getBigInt64(this.at))
      case 5:
        return this.step(4, () => this.view.getFloat32(this.at))
      case 6:
        return this.step(8, () => this.view.getFloat64(this.at))
      case 7:
        return Array.from({ length: this.int() }, () => this.byte(true))
      case 8:
        return this.string()
      case 9: {
        const inner = this.byte()
        return Array.from({ length: this.int() }, () => this.value(inner))
      }
      case 10:
        return this.compound()
      case 11:
        return Array.from({ length: this.int() }, () => this.int())
      case 12:
        return Array.from({ length: this.int() }, () => this.value(4))
      default:
        throw new Error(`the item data holds an unknown tag ${type}`)
    }
  }

  private compound(): Compound {
    const out: Compound = {}
    for (let type = this.byte(); type !== 0; type = this.byte()) {
      const name = this.string()
      out[name] = this.value(type)
    }
    return out
  }

  private step<T>(size: number, read: () => T): T {
    if (this.at + size > this.bytes.length) throw new Error("the item data ends early")
    const value = read()
    this.at += size
    return value
  }

  private byte(signed = false): number {
    return this.step(1, () => (signed ? this.view.getInt8(this.at) : this.view.getUint8(this.at)))
  }

  private int(): number {
    return this.step(4, () => this.view.getInt32(this.at))
  }

  /** Modified UTF-8 writes a NUL in two bytes and a character beyond the BMP as two surrogates of three bytes each. */
  private string(): string {
    const length = this.step(2, () => this.view.getUint16(this.at))
    const end = this.at + length
    if (end > this.bytes.length) throw new Error("the item data ends early")
    const units: number[] = []
    while (this.at < end) {
      const first = this.bytes[this.at++]
      if (first < 0x80) units.push(first)
      else if ((first & 0xe0) === 0xc0) units.push(((first & 0x1f) << 6) | (this.bytes[this.at++] & 0x3f))
      else {
        const second = this.bytes[this.at++]
        const third = this.bytes[this.at++]
        units.push(((first & 0x0f) << 12) | ((second & 0x3f) << 6) | (third & 0x3f))
      }
    }
    return String.fromCharCode(...units)
  }
}

function isCompound(value: Nbt | undefined): value is Compound {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

/** Answers the English text of a translation key from the catalogue, which names every key an entry has. */
export function translationsOf(game: GameData | undefined): Map<string, string> {
  const out = new Map<string, string>()
  for (const entries of Object.values(game?.registries ?? {}))
    for (const entry of entries) if (entry.key !== undefined && entry.text !== undefined) out.set(entry.key, entry.text)
  return out
}

/**
 * Flattens a text component to its plain text: a string, a list read one after another, or a compound with its
 * `extra`. A translation fills its `%s` and `%1$s` from `with`, and falls back to its `fallback`, then to its key.
 */
export function plainText(component: Nbt | undefined, translations: ReadonlyMap<string, string>): string {
  if (component === undefined) return ""
  if (Array.isArray(component)) return component.map((part) => plainText(part, translations)).join("")
  if (!isCompound(component)) return String(component)
  const keys = Object.keys(component)
  // A list of mixed types wraps each element in a compound with an empty key.
  if (keys.length === 1 && keys[0] === "") return plainText(component[""], translations)
  const extra = Array.isArray(component.extra) ? plainText(component.extra, translations) : ""
  if (typeof component.text === "string") return component.text + extra
  if (typeof component.translate === "string") {
    const fallback = typeof component.fallback === "string" ? component.fallback : undefined
    const template = translations.get(component.translate) ?? fallback ?? component.translate
    const args = Array.isArray(component.with) ? component.with.map((arg) => plainText(arg, translations)) : []
    return fill(template, args) + extra
  }
  if (typeof component.keybind === "string") return component.keybind + extra
  return extra
}

function fill(template: string, args: string[]): string {
  let next = 0
  return template.replace(/%(?:(\d+)\$)?([s%])/g, (_, position: string | undefined, kind: string) => {
    if (kind === "%") return "%"
    const index = position === undefined ? next++ : Number(position) - 1
    return args[index] ?? ""
  })
}

/**
 * Answers what Steward shows of a stored item. `names` answers the English name of an entry of a registry, and
 * `translations` the text of a translation key. A level of 1 shows the name alone, as for Mending.
 */
export function detailsOf(
  item: StoredItem,
  names: (registry: string, id: string) => string | undefined,
  translations: ReadonlyMap<string, string>,
): ItemDetails {
  const parts = item.components
  const named = parts[MINECRAFT + "custom_name"] ?? parts[MINECRAFT + "item_name"]
  const name = named === undefined ? undefined : plainText(named, translations).trim() || undefined
  const enchantments = [
    ...enchantmentsOf(parts[MINECRAFT + "enchantments"]),
    ...enchantmentsOf(parts[MINECRAFT + "stored_enchantments"]),
  ].map(([id, level]) => {
    const label = names("enchantment", id) ?? nameOfId(id)
    return level === 1
      ? label
      : t("steward.game.enchantment", { name: label, level: ROMAN[level - 1] ?? String(level) })
  })
  const lore = parts[MINECRAFT + "lore"]
  const lines: ItemLine[] = Array.isArray(lore)
    ? lore.map((line) => ({ text: plainText(line, translations), lore: !upright(line) }))
    : []
  lines.push(...attributeLines(parts[MINECRAFT + "attribute_modifiers"]))
  if (parts[MINECRAFT + "unbreakable"] !== undefined) lines.push({ text: t("steward.game.unbreakable") })
  const damage = parts[MINECRAFT + "damage"]
  if (typeof damage === "number" && damage > 0) lines.push({ text: t("steward.game.damaged", { damage }) })
  const colour = parts[MINECRAFT + "dyed_color"]
  const rgb = isCompound(colour) ? colour.rgb : colour
  if (typeof rgb === "number") lines.push({ text: t("steward.game.dyed", { colour: hex(rgb) }) })
  const model = parts[MINECRAFT + "item_model"]
  if (typeof model === "string") lines.push({ text: t("steward.game.item-model", { model }) })
  const modelData = modelDataOf(parts[MINECRAFT + "custom_model_data"])
  if (modelData !== "") lines.push({ text: t("steward.game.custom-model-data", { values: modelData }) })
  for (const [key, value] of Object.entries(parts)) {
    if (SHOWN_ELSEWHERE.has(key) || key.startsWith("!")) continue
    const label = nameOfId(key)
    lines.push({
      text:
        typeof value === "string" || typeof value === "number" || typeof value === "bigint"
          ? t("steward.game.component", { name: label, value: shownNumber(value) })
          : label,
    })
  }
  return { name, enchantments, lines }
}

/** Answers each enchantment with its level, from a map of id to level or the older `levels` compound. */
function enchantmentsOf(value: Nbt | undefined): [string, number][] {
  if (!isCompound(value)) return []
  const levels = isCompound(value.levels) ? value.levels : value
  return Object.entries(levels).flatMap(([id, level]) => (typeof level === "number" ? [[gameKey(id), level]] : []))
}

function attributeLines(value: Nbt | undefined): ItemLine[] {
  const modifiers = Array.isArray(value) ? value : isCompound(value) ? value.modifiers : undefined
  if (!Array.isArray(modifiers)) return []
  return modifiers.filter(isCompound).flatMap((modifier) => {
    const { type, amount, operation, slot } = modifier
    if (typeof type !== "string" || typeof amount !== "number") return []
    const percent = operation === "add_multiplied_base" || operation === "add_multiplied_total"
    const shown = (percent ? amount * 100 : amount).toLocaleString("en", {
      signDisplay: "always",
      maximumFractionDigits: 2,
    })
    const args = { amount: percent ? `${shown}%` : shown, attribute: nameOfId(type) }
    return [
      {
        text:
          typeof slot === "string" && slot !== "any"
            ? t("steward.game.attribute-worn", { ...args, slot })
            : t("steward.game.attribute", args),
      },
    ]
  })
}

function modelDataOf(value: Nbt | undefined): string {
  if (!isCompound(value)) return typeof value === "number" ? String(value) : ""
  const list = (key: string) => (Array.isArray(value[key]) ? value[key] : [])
  return [
    ...list("floats").flatMap((float) => (typeof float === "number" ? [shownNumber(float)] : [])),
    ...list("flags").map((flag) => String(flag === 1)),
    ...list("strings").filter((text) => typeof text === "string"),
    ...list("colors").flatMap((colour) => (typeof colour === "number" ? [hex(colour)] : [])),
  ].join(", ")
}

/** The game sets lore in italics unless the line, or its wrapped element, turns them off. */
function upright(line: Nbt): boolean {
  if (!isCompound(line)) return false
  return line.italic === 0 || (Object.keys(line).length === 1 && line[""] !== undefined && upright(line[""]))
}

/** A float reads back widened to a double; seven significant digits give the value the game shows. */
function shownNumber(value: number | bigint | string): string {
  return typeof value === "number" && !Number.isInteger(value) ? String(Number(value.toPrecision(7))) : String(value)
}

function hex(rgb: number): string {
  return `#${(rgb & 0xffffff).toString(16).padStart(6, "0")}`
}
