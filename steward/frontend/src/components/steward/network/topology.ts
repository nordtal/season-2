import type { NetworkMap } from "@/lib/api"

/**
 * What is wired to what, as `/api/topology` reads it from the labels in `compose.yml`. Every arrangement draws this model.
 *
 * The boxes are the served service names plus {@link INGRESS}, which is drawn apart because it is no container.
 */

/** The box that stands for people and not for a container. */
export const INGRESS = "players" as const

/** A served service name, or {@link INGRESS}. */
export type NodeId = string

/**
 * Why an edge exists and how prominently it is drawn: `data` edges sit behind the `traffic` path.
 */
export type EdgeKind = "traffic" | "data"

export type Edge = { from: NodeId; to: NodeId; kind: EdgeKind }

export type Section = { id: string; title: string; members: string[] }

export type Topology = {
  /** Every drawn service, section by section, in the sidebar's order. */
  names: string[]
  edges: Edge[]
  /**
   * The sections of the phone's table in reading order: from the players inwards, storage last.
   */
  sections: Section[]
  /** The services that keep their data in {@link sink}, drawn as one bundle. */
  storers: string[]
  /**
   * The one service that everything stores in, or `undefined` when there is none or more than one.
   */
  sink?: string
}

/**
 * Returns the served map as edges and sections. Nothing draws a box that is missing from the map.
 */
export function topologyOf(map: NetworkMap): Topology {
  const boxes = map.services
  const edges: Edge[] = [
    ...boxes.filter((box) => box.entry).map((box): Edge => ({ from: INGRESS, to: box.name, kind: "traffic" })),
    ...boxes.flatMap((box) => box.reaches.map((to): Edge => ({ from: box.name, to, kind: "traffic" }))),
    ...boxes.flatMap((box) => box.storesIn.map((to): Edge => ({ from: box.name, to, kind: "data" }))),
  ]
  const stores = new Set(boxes.flatMap((box) => box.storesIn))
  const depth = depths(edges)
  const order = new Map<string, number>()
  for (const box of boxes) {
    /**
     * Storage ranks after everything else. A service that no traffic reaches ranks after every service that traffic reaches.
     */
    const rank = stores.has(box.name) ? Infinity : (depth.get(box.name) ?? boxes.length)
    order.set(box.section, Math.min(order.get(box.section) ?? Infinity, rank))
  }
  const titles = [...new Set(boxes.map((box) => box.section))].toSorted(
    (one, other) => order.get(one)! - order.get(other)!,
  )
  const sections = titles.map((title) => ({
    id: title.toLowerCase(),
    title,
    members: boxes.filter((box) => box.section === title).map((box) => box.name),
  }))
  return {
    names: sections.flatMap((section) => section.members),
    edges,
    sections,
    storers: boxes.filter((box) => box.storesIn.length > 0).map((box) => box.name),
    sink: stores.size === 1 ? [...stores][0] : undefined,
  }
}

/** Returns how many traffic hops each box is from the players. */
function depths(edges: readonly Edge[]): Map<string, number> {
  const depth = new Map<string, number>([[INGRESS, 0]])
  const queue: string[] = [INGRESS]
  while (queue.length > 0) {
    const from = queue.shift()!
    for (const edge of edges) {
      if (edge.kind === "traffic" && edge.from === from && !depth.has(edge.to)) {
        depth.set(edge.to, depth.get(from)! + 1)
        queue.push(edge.to)
      }
    }
  }
  return depth
}

/**
 * Returns the names that a hand-written arrangement gets wrong against the served names: missing, unknown or repeated.
 */
export function layoutFaults(placed: readonly string[], names: readonly string[]): string[] {
  const faults: string[] = []
  const seen = new Set<string>()
  for (const id of placed) {
    if (seen.has(id)) faults.push(`${id} is placed twice`)
    seen.add(id)
    if (id !== INGRESS && !names.includes(id)) {
      faults.push(`${id} is not a served service`)
    }
  }
  for (const name of names) {
    if (!seen.has(name)) faults.push(`${name} is placed nowhere`)
  }
  if (!seen.has(INGRESS)) faults.push("players is placed nowhere")
  return faults
}

/**
 * Returns the tag that a container runs, or `#` and the first seven characters of a digest when it has none.
 *
 * The `#` keeps a short all-digit hex from reading as a number next to the drift mark.
 */
export function imageTag(image: string | undefined): string {
  if (!image) return "\u2013"
  const [reference, digest] = image.split("@")
  if (reference.startsWith("sha256:")) {
    return `#${reference.slice("sha256:".length, "sha256:".length + 7)}`
  }
  const name = reference.slice(reference.lastIndexOf("/") + 1)
  const colon = name.lastIndexOf(":")
  if (colon !== -1) return name.slice(colon + 1)
  if (digest) return `#${digest.replace(/^sha256:/, "").slice(0, 7)}`
  /** Docker's own default when a reference carries no tag. */
  return "latest"
}
