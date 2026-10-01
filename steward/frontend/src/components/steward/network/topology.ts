import { SERVICES, type ServiceName } from "@/app/navigation"

/**
 * What is wired to what, read off `compose.yml`: the one model every arrangement draws.
 *
 * Boxes are `navigation.ts` service names plus {@link INGRESS}, drawn apart as no container.
 */

/** The box that is people rather than a container. */
export const INGRESS = "players" as const

export type NodeId = ServiceName | typeof INGRESS

/** Why an edge exists, and how loudly it is drawn: `data` edges sit behind the `traffic` path. */
export type EdgeKind = "traffic" | "data"

export type Edge = { from: NodeId; to: NodeId; kind: EdgeKind }

/** The services compose passes a database URL to. */
export const DATABASE_CLIENTS: ServiceName[] = ["smp", "hunger-games", "limbo", "proxy", "discord-bot", "steward"]

/**
 * The phone's table sections, in reading order, with no "connected to" column.
 *
 * `discord-bot` only talks to the database, so it has its own section; `players` gets no row.
 */
export const SECTIONS: Array<{ id: string; title: string; members: ServiceName[] }> = [
  { id: "entry", title: "Entry", members: ["caddy", "proxy"] },
  { id: "paper", title: "Paper", members: ["smp", "hunger-games", "limbo"] },
  {
    id: "steward",
    title: "Steward",
    members: ["steward", "steward-agent", "steward-bunq"],
  },
  { id: "discord", title: "Discord", members: ["discord-bot"] },
  { id: "database", title: "Database", members: ["postgres"] },
]

export const EDGES: Edge[] = [
  { from: INGRESS, to: "proxy", kind: "traffic" },
  { from: INGRESS, to: "caddy", kind: "traffic" },
  { from: "caddy", to: "steward", kind: "traffic" },
  { from: "proxy", to: "smp", kind: "traffic" },
  { from: "proxy", to: "hunger-games", kind: "traffic" },
  { from: "proxy", to: "limbo", kind: "traffic" },
  { from: "steward", to: "steward-agent", kind: "traffic" },
  { from: "steward", to: "steward-bunq", kind: "traffic" },
  ...DATABASE_CLIENTS.map((client): Edge => ({ from: client, to: "postgres", kind: "data" })),
]

/** The names a hand-written arrangement gets wrong: missing, unknown or repeated, checked against `SERVICES`. */
export function layoutFaults(placed: readonly string[]): string[] {
  const faults: string[] = []
  const seen = new Set<string>()
  const known: readonly string[] = SERVICES
  for (const id of placed) {
    if (seen.has(id)) faults.push(`${id} is placed twice`)
    seen.add(id)
    if (id !== INGRESS && !known.includes(id)) {
      faults.push(`${id} is not in SERVICES`)
    }
  }
  for (const name of SERVICES) {
    if (!seen.has(name)) faults.push(`${name} is placed nowhere`)
  }
  if (!seen.has(INGRESS)) faults.push("players is placed nowhere")
  return faults
}

/**
 * The tag a container runs, or `#` and the first seven of a digest when it has none.
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
