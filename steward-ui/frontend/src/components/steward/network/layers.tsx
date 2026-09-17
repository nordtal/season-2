import { useRef } from "react"

import { useNetwork } from "./data"
import { ServiceNode } from "./node"
import { INGRESS, type NodeId } from "./topology"
import { Wires, useNodeBoxes } from "./wires"

/**
 * Draft **b** - four bands, stacked.
 *
 * Depth is the vertical axis and nothing else: what a request meets first is at the top, what it
 * ends in is at the bottom, and a box's height on the screen is how far into the stack it is. Every
 * line therefore runs downwards, which is the one thing this shape buys that the other two do not -
 * there is no line in the picture that has to be followed to work out which way it goes.
 *
 * <h2>Why the bands carry a word each</h2>
 * Four words, once, and they are the only text in the picture that is not data. Without them the
 * bands are four rows of boxes with more space between some of them than others, which is a layout,
 * not a statement. With them the vertical axis means something a reader can name.
 *
 * <h2>On a phone it is already the right shape</h2>
 * This is the draft that needs no narrow form, because a stack of bands is what a phone wants
 * anyway. Two boxes to a row, three from 448px and four from 672px, with the bands and their gaps
 * unchanged - so the picture at 390px is the picture at 1440px with the rows folded, and the lines
 * between bands stay vertical at every width. The three steps are MEASURED and not a guess: at
 * four columns in a 580px half section `network-control` truncates to `network-co…`, and at three
 * it does not. It is the safest of the three and, for the same
 * reason, the one that says least: nothing in it distinguishes the proxy from the four things
 * hanging off it.
 */

export const LAYERS: Array<{ label: string; members: NodeId[] }> = [
  { label: "edge", members: [INGRESS, "caddy", "network-control"] },
  { label: "servers", members: ["smp", "hunger-games", "limbo"] },
  {
    label: "services",
    members: ["steward-ui", "steward-worker", "steward-deployer", "discord-bot"],
  },
  { label: "data", members: ["postgres"] },
]

export function LayersDraft() {
  const host = useRef<HTMLDivElement>(null)
  const geometry = useNodeBoxes(host)
  const network = useNetwork()

  return (
    <div ref={host} className="@container relative flex flex-col gap-7">
      {LAYERS.map((layer) => (
        <div key={layer.label} className="flex flex-col gap-2">
          <span className="text-xs text-muted-foreground">{layer.label}</span>
          <div
            className={
              layer.members.length === 1
                ? "grid grid-cols-1 gap-2.5"
                : "grid grid-cols-2 gap-2.5 @md:grid-cols-3 @2xl:grid-cols-4"
            }
          >
            {layer.members.map((id) => (
              <ServiceNode
                key={id}
                id={id}
                service={network.service(id)}
                players={network.players(id)}
              />
            ))}
          </div>
        </div>
      ))}

      <Wires id="layers" geometry={geometry} />
    </div>
  )
}
