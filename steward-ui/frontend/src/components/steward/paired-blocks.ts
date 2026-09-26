import type { ConfigEntry } from "@/lib/api"

/**
 * Two sibling blocks with the same keys, drawn as one row per key (steward/130).
 *
 * <h2>The defect this closes is structural, not cosmetic</h2>
 * Till, 2026-09-20, on the crest ladder: the hours and the colours should be settable in one
 * place. What made that more than a convenience is what the two lists are: **two lists that belong
 * together by position are one list with an unwritten contract**. Whoever changed the seventh line
 * of one had to find the seventh line of the other and trust that the orders agreed - and the
 * failure, tier 7 turning gold at tier 8's hour, is invisible in both files and in a form that
 * draws them as two stacks.
 *
 * <h2>Structural, so it is not about prestige</h2>
 * Nothing here knows a file name or a key. The rule is: two `MAP` entries that are **siblings**,
 * **adjacent** in the file, and whose immediate children have the **same keys in the same order**.
 * That is exactly the shape that carries the unwritten contract, and any file that grows one gets
 * the same drawing for free. A block with an extra key, a missing one or a different order is not
 * paired at all - it falls back to two ordinary stacks, which is the honest answer, because a
 * pairing that dropped or shifted a row would be the very mistake this exists to prevent.
 *
 * <h2>Why the children have to be leaves</h2>
 * Only scalars pair. A child that is itself a `MAP` has its own children under it, and a "row"
 * holding two sub-trees is a table cell containing a table - so a pair with any non-scalar member
 * is refused rather than half-drawn.
 */
export type PairedBlocks = {
  /** The first of the two `MAP` entries, in file order. */
  left: ConfigEntry
  /** The second. */
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
    // Adjacent in the FILE, not merely consecutive among the maps: a scalar standing between the
    // two blocks means they are not the pair somebody wrote as one idea, and pairing them anyway
    // would move that scalar's meaning.
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
    // The right block cannot also be the left of the next pair: it is already spoken for, and a
    // chain of three blocks is a table this rule does not claim to draw.
    index += 1
  }
  return found
}

/** Every path a pair consumes - the two headings and all of their children. */
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

/** Everything nested under a `MAP`, at any depth - what stands between it and the next sibling. */
function descendantsOf(entries: ConfigEntry[], map: ConfigEntry): ConfigEntry[] {
  return entries.filter((entry) => entry.path.startsWith(map.path + "."))
}

/** The immediate children of a `MAP`, in file order. */
function childrenOf(entries: ConfigEntry[], map: ConfigEntry): ConfigEntry[] {
  const prefix = map.path + "."
  return entries.filter((entry) => entry.path.startsWith(prefix) && !entry.path.slice(prefix.length).includes("."))
}

function notALeaf(entry: ConfigEntry): boolean {
  return entry.kind !== "SCALAR"
}
