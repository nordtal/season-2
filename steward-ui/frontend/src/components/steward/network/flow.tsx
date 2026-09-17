import { useRef } from "react"

import { useNetwork } from "./data"
import { ServiceNode } from "./node"
import { INGRESS, type NodeId } from "./topology"
import { Wires, useNodeBoxes } from "./wires"

/**
 * Draft **a** - a directed flow, left to right.
 *
 * The picture is a sentence: somebody arrives on the left, passes the front door, lands on a
 * server, and everything on the way writes to the one box along the bottom. It is the only one of
 * the three that draws arrowheads, because it is the only one making a claim about direction -
 * layers and a ring are both about what sits next to what.
 *
 * <h2>Postgres is a bar, not a column</h2>
 * Seven of the ten services hold a database connection, so as a fifth column it would be a single
 * box with seven lines converging on it from the left and four columns squeezed to make room. As a
 * bar under the whole thing, the lines drop straight down, it costs no width at all, and
 * "everything into the database" is drawn as the thing it is: a floor, not a station.
 *
 * <h2>On a phone the sentence turns ninety degrees</h2>
 * At 390px the four stages stack, so the flow runs top to bottom instead of left to right, two
 * boxes to a row inside each stage, and the arrowheads still point the way traffic goes. Nothing
 * about the edges changes: they are measured from wherever the boxes end up, so the same eight
 * traffic lines that were horizontal are now vertical. The stage gaps are wider than the gaps
 * inside a stage, which is what keeps four stacked lists from reading as one long one. It is the
 * honest narrow form of this shape - the alternative, keeping the columns and scrolling sideways,
 * hides the right-hand half of the picture behind a gesture nobody makes on a dashboard.
 *
 * <h2>The width it wants, and the thing to know before choosing it</h2>
 * **The columns need about 710px and take 768.** MEASURED in a browser on 2026-09-17, not
 * estimated: below that the names truncate, and a network view whose centre box says
 * `network-co…` is worse than one that stacked. The consequence is the one fact this draft owes
 * whoever picks it - **half of a 1440px page is 580px, so in the split section steward/81 plans
 * this draft would draw its stacked form, not its columns.** It is left to right on the drafts
 * page, on a wide window and in any container over 768px; it is a top-to-bottom tree in a half
 * section. That is a reason to choose it or not to, and it is not a defect that can be worked
 * around: four boxes with those names do not fit in 580px, whatever the layout does.
 */

/** The four stages, in order. Checked against `SERVICES` by `network.test.tsx`, never by eye. */
export const FLOW: NodeId[][] = [
  [INGRESS],
  ["caddy", "network-control"],
  ["steward-ui", "smp", "hunger-games", "limbo"],
  ["steward-worker", "steward-deployer", "discord-bot"],
]

/** The floor every stage drops into. */
export const FLOW_SINK: NodeId = "postgres"

export function FlowDraft() {
  const host = useRef<HTMLDivElement>(null)
  const geometry = useNodeBoxes(host)
  const network = useNetwork()

  return (
    <div ref={host} className="@container relative">
      {/* The first stage holds one short word and the other three hold names like `hunger-games`,
          so four equal columns would spend the same width on `players` as on the longest name in
          the stack. MEASURED 2026-09-17 in a browser: `network-control` with a player count beside
          it needs about 168px before the name truncates, so the four columns need roughly 710px
          between them - and `0.75fr` for the first is what buys the other three those 168 while
          leaving the word `players` whole. 768px is the container step above 710, and it is a
          container query rather than a screen one because the number that matters is the width
          this draft is given, not the width of the window. */}
      <div className="grid grid-cols-1 gap-x-3 gap-y-7 @3xl:grid-cols-[0.75fr_1fr_1fr_1fr] @3xl:gap-y-3">
        {FLOW.map((stage, index) => (
          // Two boxes to a row while the stages are stacked, one per row once they are columns.
          // MEASURED at 390px on 2026-09-17: with every box full width the whole picture is one
          // vertical line through eleven boxes, and the segments showing in the gaps read as a
          // chain - network-control to steward-ui to smp, which is not an edge that exists. Two
          // columns give the lines out of one box three different x positions to land on, which is
          // what makes them look like a fan again instead of a spine.
          <div key={index} className="grid grid-cols-2 content-center gap-2.5 @3xl:grid-cols-1">
            {stage.map((id) => (
              <ServiceNode
                key={id}
                id={id}
                service={network.service(id)}
                players={network.players(id)}
              />
            ))}
          </div>
        ))}
      </div>

      <div className="mt-7">
        <ServiceNode id={FLOW_SINK} service={network.service(FLOW_SINK)} />
      </div>

      <Wires id="flow" geometry={geometry} arrows />
    </div>
  )
}
