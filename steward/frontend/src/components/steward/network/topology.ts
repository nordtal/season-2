import type { NetworkMap } from "@/lib/api"
import { NO_VALUE } from "@/lib/format"
import { servicePlace } from "@/lib/services"

/**
 * The boxes are the served service names plus {@link INGRESS}, which is drawn apart because it is no container.
 */

export const INGRESS = "players" as const

export type NodeId = string

/** `data` edges sit behind the `traffic` path. */
export type EdgeKind = "traffic" | "data"

export type Edge = { from: NodeId; to: NodeId; kind: EdgeKind }

export type Section = { id: string; title: string; members: string[] }

export type Topology = {
  names: string[]
  edges: Edge[]
  sections: Section[]
  storers: string[]
  /** `undefined` when there is none or more than one. */
  sink?: string
}

export function topologyOf(map: NetworkMap): Topology {
  const boxes = map.services
  const edges: Edge[] = [
    ...boxes.filter((box) => box.entry).map((box): Edge => ({ from: INGRESS, to: box.name, kind: "traffic" })),
    ...boxes.flatMap((box) => box.reaches.map((to): Edge => ({ from: box.name, to, kind: "traffic" }))),
    ...boxes.flatMap((box) => box.storesIn.map((to): Edge => ({ from: box.name, to, kind: "data" }))),
  ]
  const stores = new Set(boxes.flatMap((box) => box.storesIn))
  const ordered = boxes.toSorted((one, other) => servicePlace(one.name) - servicePlace(other.name))
  /** A section stands where its first member stands in the one order of services. */
  const titles = [...new Set(ordered.map((box) => box.section))]
  const sections = titles.map((title) => ({
    id: title.toLowerCase(),
    title,
    members: ordered.filter((box) => box.section === title).map((box) => box.name),
  }))
  return {
    names: sections.flatMap((section) => section.members),
    edges,
    sections,
    storers: boxes.filter((box) => box.storesIn.length > 0).map((box) => box.name),
    sink: stores.size === 1 ? [...stores][0] : undefined,
  }
}

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

/** The `#` keeps a short all-digit hex from reading as a number next to the drift mark. */
export function imageTag(image: string | undefined): string {
  if (!image) return NO_VALUE
  const [reference, digest] = image.split("@")
  if (reference.startsWith("sha256:")) {
    return `#${reference.slice("sha256:".length, "sha256:".length + 7)}`
  }
  const name = reference.slice(reference.lastIndexOf("/") + 1)
  const colon = name.lastIndexOf(":")
  if (colon !== -1) return name.slice(colon + 1)
  if (digest) return `#${digest.replace(/^sha256:/, "").slice(0, 7)}`
  return "latest"
}
