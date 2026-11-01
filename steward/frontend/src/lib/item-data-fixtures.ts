/** Writes item data as Paper serialises a stack: a gzipped, named NBT compound, in Base64. Tests alone use it. */

/** One NBT tag to write, typed as the game writes it. */
export type Tag =
  | { type: "byte" | "short" | "int" | "float" | "double"; value: number }
  | { type: "long"; value: bigint }
  | { type: "string"; value: string }
  | { type: "list"; of: number; value: Tag[] }
  | { type: "compound"; value: Record<string, Tag> }
  | { type: "int-array"; value: number[] }

const ID = {
  byte: 1,
  short: 2,
  int: 3,
  long: 4,
  float: 5,
  double: 6,
  string: 8,
  list: 9,
  compound: 10,
  "int-array": 11,
}

export const byte = (value: number): Tag => ({ type: "byte", value })
export const int = (value: number): Tag => ({ type: "int", value })
export const float = (value: number): Tag => ({ type: "float", value })
export const double = (value: number): Tag => ({ type: "double", value })
export const long = (value: bigint): Tag => ({ type: "long", value })
export const text = (value: string): Tag => ({ type: "string", value })
export const compound = (value: Record<string, Tag>): Tag => ({ type: "compound", value })
export const list = (value: Tag[]): Tag => ({ type: "list", of: value.length === 0 ? 0 : ID[value[0].type], value })

/** A stack as Paper writes it: its id, count, components and the data version. */
export function stack(id: string, components: Record<string, Tag> = {}): Tag {
  return compound({ id: text(id), count: int(1), components: compound(components), DataVersion: int(4671) })
}

/** Answers `root` as Base64 of its gzipped NBT. */
export async function itemData(root: Tag): Promise<string> {
  const out: number[] = []
  out.push(ID.compound)
  writeString(out, "")
  writePayload(out, root)
  const packed = new Response(
    new Response(Uint8Array.from(out)).body?.pipeThrough(new CompressionStream("gzip")),
  ).arrayBuffer()
  const bytes = new Uint8Array(await packed)
  return btoa(String.fromCharCode(...bytes))
}

function writePayload(out: number[], tag: Tag) {
  const view = new DataView(new ArrayBuffer(8))
  const bytes = (size: number) => out.push(...new Uint8Array(view.buffer, 0, size))
  switch (tag.type) {
    case "byte":
      view.setInt8(0, tag.value)
      return bytes(1)
    case "short":
      view.setInt16(0, tag.value)
      return bytes(2)
    case "int":
      view.setInt32(0, tag.value)
      return bytes(4)
    case "float":
      view.setFloat32(0, tag.value)
      return bytes(4)
    case "double":
      view.setFloat64(0, tag.value)
      return bytes(8)
    case "long":
      view.setBigInt64(0, tag.value)
      return bytes(8)
    case "string":
      return writeString(out, tag.value)
    case "list":
      out.push(tag.of)
      writePayload(out, int(tag.value.length))
      for (const item of tag.value) writePayload(out, item)
      return
    case "int-array":
      writePayload(out, int(tag.value.length))
      for (const item of tag.value) writePayload(out, int(item))
      return
    case "compound":
      for (const [name, value] of Object.entries(tag.value)) {
        out.push(ID[value.type])
        writeString(out, name)
        writePayload(out, value)
      }
      out.push(0)
  }
}

/** Modified UTF-8, as Java's `DataOutput.writeUTF` writes it. */
function writeString(out: number[], value: string) {
  const encoded: number[] = []
  for (let index = 0; index < value.length; index++) {
    const unit = value.charCodeAt(index)
    if (unit !== 0 && unit < 0x80) encoded.push(unit)
    else if (unit < 0x800) encoded.push(0xc0 | (unit >> 6), 0x80 | (unit & 0x3f))
    else encoded.push(0xe0 | (unit >> 12), 0x80 | ((unit >> 6) & 0x3f), 0x80 | (unit & 0x3f))
  }
  out.push(encoded.length >> 8, encoded.length & 0xff, ...encoded)
}
