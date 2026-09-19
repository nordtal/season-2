import { SERVICES, type ServiceName } from "@/app/navigation"

/**
 * What is wired to what, and who may appear as a box - the one model all three drafts of
 * steward/81 draw.
 *
 * <h2>The names are not a second list</h2>
 * Every box is a {@link ServiceName} out of `navigation.ts`, which is the list the sidebar and the
 * command palette already render. A network view holding its own ten strings would be the second
 * copy that quietly stops matching compose the day an eleventh service arrives, so the layouts
 * below name services and `layoutCovers` checks that they name all of them.
 *
 * <h2>The one box that is not a service</h2>
 * {@link INGRESS} is where the traffic comes from. It is not a container, has no health and no
 * image, and it is drawn differently for exactly that reason - but without it the graph starts at
 * the proxy, and "the proxy is the front door" is the fact the picture is supposed to carry.
 *
 * <h2>Where the edges come from</h2>
 * Read off `compose.yml` on 2026-09-17, not invented:
 *
 * - `players` reaches `proxy` on 25565 and `caddy` on 443, the only two published ports.
 * - `caddy` proxies `steward-ui`; `steward-ui` calls `steward-worker` and `steward-deployer` over
 *   the internal network, which is what `ApiError.where` names when one of them does not answer.
 * - `proxy` sends a player onwards to `limbo`, `hunger-games` or `smp`.
 * - `db` edges are exactly the services compose hands `NORDTAL_*_DATABASE_JDBC_URL` to: seven of
 *   the ten. `caddy`, `steward-deployer` and `postgres` itself get no such variable and get no
 *   line.
 */

/** The box that is people rather than a container. */
export const INGRESS = "players" as const

export type NodeId = ServiceName | typeof INGRESS

/**
 * Why an edge exists, which is also how loudly it is drawn.
 *
 * `traffic` is the path a request takes and is the story of the picture. `data` is every service's
 * line to the database - seven of them, all ending in the same box - and drawn at the same weight
 * it would swamp the traffic path it is supposed to sit behind.
 */
export type EdgeKind = "traffic" | "data"

export type Edge = { from: NodeId; to: NodeId; kind: EdgeKind }

/** The services compose passes a database URL to. */
export const DATABASE_CLIENTS: ServiceName[] = [
  "smp",
  "hunger-games",
  "limbo",
  "proxy",
  "discord-bot",
  "steward-worker",
  "steward-ui",
]

export const EDGES: Edge[] = [
  { from: INGRESS, to: "proxy", kind: "traffic" },
  { from: INGRESS, to: "caddy", kind: "traffic" },
  { from: "caddy", to: "steward-ui", kind: "traffic" },
  { from: "proxy", to: "smp", kind: "traffic" },
  { from: "proxy", to: "hunger-games", kind: "traffic" },
  { from: "proxy", to: "limbo", kind: "traffic" },
  { from: "steward-ui", to: "steward-worker", kind: "traffic" },
  { from: "steward-ui", to: "steward-deployer", kind: "traffic" },
  ...DATABASE_CLIENTS.map((client): Edge => ({ from: client, to: "postgres", kind: "data" })),
]

/**
 * Which services a draft has decided to place, checked against `SERVICES` rather than trusted.
 *
 * A layout is a hand-written arrangement, so it is the place a new compose service goes missing -
 * silently, because a box that is not drawn looks exactly like a box that is somewhere further
 * down. This returns what is wrong so a test can say which name it is.
 */
export function layoutFaults(placed: readonly NodeId[]): string[] {
  const faults: string[] = []
  const seen = new Set<NodeId>()
  for (const id of placed) {
    if (seen.has(id)) faults.push(`${id} is placed twice`)
    seen.add(id)
    if (id !== INGRESS && !SERVICES.includes(id as ServiceName)) {
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
 * The tag a container is running, which is as much of an image reference as fits under a name.
 *
 * `ghcr.io/nordtal/smp:1.4.0` is 24 characters and `1.4.0` is five, and the part that differs
 * between two deployments is always the five. A reference pinned by digest has no tag to print, so
 * the first seven of the digest stand in - short, and still enough to tell two of them apart.
 *
 * **A bare image id is the case that is actually on this host**, not a theoretical one: measured
 * 2026-09-17, `steward-worker`'s row carries
 * `sha256:334951d4c54754fa0bcc40bc7e483f2af78c7244fbefc28eff775ffa40c1ce07` and nothing else,
 * because its tag was rebuilt without the container being recreated - the same situation
 * `DriftBadge`'s `UNKNOWN` text describes. Sixty-four hex characters under a name is not a version,
 * it is a line of noise as wide as the box, so it is shortened like any other digest.
 *
 * **A shortened digest is prefixed with `#`, and that prefix is not decoration.** Seven hex
 * characters with nothing else around them reads as a number - the network view's first review
 * (steward/81, 2026-09-17) found exactly that: `steward-worker` showing `334951d` next to the
 * "not compared" mark was read as "334951 days", because a hex string with no letters near its
 * front end is indistinguishable from a large number at a glance. `#` is what a reader's eye
 * already parses as "identifier, not quantity" - a version tag never carries one, so it also keeps
 * the two cases visually apart from each other, not just from a duration.
 */
export function imageTag(image: string | undefined): string {
  if (!image) return "–"
  const [reference, digest] = image.split("@")
  if (/^sha256:/.test(reference)) {
    return `#${reference.slice("sha256:".length, "sha256:".length + 7)}`
  }
  const name = reference.slice(reference.lastIndexOf("/") + 1)
  const colon = name.lastIndexOf(":")
  if (colon !== -1) return name.slice(colon + 1)
  if (digest) return `#${digest.replace(/^sha256:/, "").slice(0, 7)}`
  // Docker's own default when a reference carries no tag. Printing the repository name here
  // instead would put `caddy` on a line whose whole job is to say which version of caddy.
  return "latest"
}
