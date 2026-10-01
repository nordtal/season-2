import type { ConfigEntry } from "@/lib/api"

/**
 * Two adjacent sibling `MAP` blocks with the same scalar keys in the same order, drawn as one row per key.
 *
 * Anything else stays two stacks, since a pairing that shifted a row is the mistake this prevents.
 */
export type PairedBlocks = {
  left: ConfigEntry
  right: ConfigEntry
  /** One per shared key, in the left block's order. */
  rows: Array<{ key: string; left: ConfigEntry; right: ConfigEntry }>
}

export function pairedBlocks(entries: ConfigEntry[]): PairedBlocks[] {
  const found: PairedBlocks[] = []
  const maps = entries.filter((entry) => entry.kind === "MAP")

  for (let index = 0; index + 1 < maps.length; index += 1) {
    const left = maps[index]
    const right = maps[index + 1]
    if (parentOf(left) !== parentOf(right)) continue
    /** Adjacent in the file, since a scalar between the blocks means they were not written as one idea. */
    if (entries.indexOf(right) !== entries.indexOf(left) + descendantsOf(entries, left).length + 1) {
      continue
    }

    const leftChildren = childrenOf(entries, left)
    const rightChildren = childrenOf(entries, right)
    if (leftChildren.length === 0 || leftChildren.length !== rightChildren.length) continue
    if (leftChildren.some(notALeaf) || rightChildren.some(notALeaf)) continue
    if (leftChildren.some((child, at) => child.key !== rightChildren[at].key)) continue

    found.push({
      left,
      right,
      rows: leftChildren.map((child, at) => ({
        key: child.key,
        left: child,
        right: rightChildren[at],
      })),
    })
    /** The right block cannot start the next pair, since a chain of three is not a table this draws. */
    index += 1
  }
  return found
}

/** Every path a pair consumes: the two headings and all of their children. */
export function pairedPaths(pairs: PairedBlocks[]): Set<string> {
  const taken = new Set<string>()
  for (const pair of pairs) {
    taken.add(pair.left.path)
    taken.add(pair.right.path)
    for (const row of pair.rows) {
      taken.add(row.left.path)
      taken.add(row.right.path)
    }
  }
  return taken
}

function parentOf(entry: ConfigEntry): string {
  return entry.path.includes(".") ? entry.path.slice(0, entry.path.lastIndexOf(".")) : ""
}

/** Everything nested under a `MAP`, at any depth, up to its next sibling. */
function descendantsOf(entries: ConfigEntry[], map: ConfigEntry): ConfigEntry[] {
  return entries.filter((entry) => entry.path.startsWith(map.path + "."))
}

function childrenOf(entries: ConfigEntry[], map: ConfigEntry): ConfigEntry[] {
  const prefix = map.path + "."
  return entries.filter((entry) => entry.path.startsWith(prefix) && !entry.path.slice(prefix.length).includes("."))
}

function notALeaf(entry: ConfigEntry): boolean {
  return entry.kind !== "SCALAR"
}
