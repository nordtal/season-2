import { useRef } from "react"

import { useNetwork } from "./data"
import { ServiceNode } from "./node"
import { INGRESS, type NodeId } from "./topology"
import { Wires, useNodeBoxes } from "./wires"

/**
 * Draft **c** - a ring around the proxy.
 *
 * `network-control` sits in the middle and everything a player's connection touches is arranged
 * around it: the people coming in at the top, the front door to the right, the three servers filling
 * the rest of the circle. It is the only one of the three that says out loud which box the network
 * is built around - in a flow the proxy is a station like any other, in a stack it is one name in a
 * band of three.
 *
 * <h2>The ring is five, and the rest is a floor under it</h2>
 * Ten boxes on one circle needs a diameter no half of a section has, and a ring that has to shrink
 * its boxes to close is a ring nobody can read. So only what the proxy is actually wired to goes on
 * it, and the four boxes that have nothing to do with a Minecraft connection - the three Steward
 * containers and the bot - sit in a row underneath with the database. The seven lines into
 * `postgres` cross the circle rather than avoid it, which is honest: they do cross.
 *
 * <h2>Spokes, not curves</h2>
 * A curve that leaves the "nearest side" of a box sitting at four o'clock leaves from the wrong
 * corner, so this draft routes straight centre to centre and lets the opaque boxes hide the ends.
 *
 * <h2>On a phone it is not a ring, and it cannot be</h2>
 * Below 660px of **container** width the circle would need boxes narrower than the word
 * `hunger-games`, so the ring opens into a fan: the proxy on top, its five neighbours in two
 * columns under it, then the floor. The lines then run downwards from one box to five, which is the
 * same statement the ring makes and the same one the picture had at 1440px - what is lost is the
 * symmetry, which was the decoration, not the information. The threshold is the container's width
 * and not the window's, because this draft is headed for one half of a split section.
 */

/** The centre, and the reason this draft exists. */
export const HUB: NodeId = "network-control"

/**
 * The ring, at fixed angles rather than evenly spread by index.
 *
 * Degrees, zero at three o'clock, growing clockwise. The people are at the top because that is
 * where a reader starts; the front door is to their right because it is the other half of the same
 * arrival; the three servers take the bottom, where the traffic ends up.
 */
export const RING: Array<{ id: NodeId; angle: number }> = [
  { id: INGRESS, angle: -90 },
  { id: "caddy", angle: -18 },
  { id: "limbo", angle: 54 },
  { id: "hunger-games", angle: 126 },
  { id: "smp", angle: 198 },
]

/** Everything a player's connection never touches, plus the box all of it writes to. */
export const FLOOR: NodeId[] = [
  "steward-ui",
  "steward-worker",
  "steward-deployer",
  "discord-bot",
  "postgres",
]

/**
 * Below this many pixels of container width the ring opens into a fan. See the class comment.
 *
 * It is the width of the ring's own box ({@link RING_BOX}) plus a little, not the width of the
 * page: the circle is capped so that a 1440px screen draws a circle rather than an ellipse as wide
 * as the content, and once the container is narrower than the cap there is no circle left to draw.
 */
const RING_NEEDS = 660

/** How wide the circle is allowed to get. Wider, and it stops reading as a ring. */
const RING_BOX = "max-w-[40rem]"

export function RingDraft() {
  const host = useRef<HTMLDivElement>(null)
  const geometry = useNodeBoxes(host)
  const network = useNetwork()
  // 0 until the first measurement, so the fan is what renders first and what a test sees. That is
  // the right way round: the fan is the form that works at any width.
  const round = geometry.width >= RING_NEEDS

  const node = (id: NodeId, className?: string) => (
    <ServiceNode
      key={id}
      id={id}
      service={network.service(id)}
      players={network.players(id)}
      className={className}
    />
  )

  return (
    <div ref={host} className="@container relative flex flex-col gap-7">
      {round ? (
        <div className={`relative mx-auto h-[25rem] w-full ${RING_BOX}`}>
          <div className="absolute left-1/2 top-1/2 w-[10.5rem] -translate-x-1/2 -translate-y-1/2">
            {node(HUB)}
          </div>
          {RING.map(({ id, angle }) => {
            const radians = (angle * Math.PI) / 180
            return (
              <div
                key={id}
                className="absolute w-[10.5rem] -translate-x-1/2 -translate-y-1/2"
                style={{
                  // 32% of the width and 38% of the height: the box is wider than it is tall, so
                  // one percentage for both would be an ellipse. These two are what keep the five
                  // boxes on a circle and inside the edges at the cap above.
                  left: `calc(50% + ${(Math.cos(radians) * 32).toFixed(2)}%)`,
                  top: `calc(50% + ${(Math.sin(radians) * 38).toFixed(2)}%)`,
                }}
              >
                {node(id)}
              </div>
            )
          })}
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          <div className="grid grid-cols-1">{node(HUB)}</div>
          <div className="grid grid-cols-2 gap-2.5">{RING.map(({ id }) => node(id))}</div>
        </div>
      )}

      <div className="grid grid-cols-2 gap-2.5 @2xl:grid-cols-5">{FLOOR.map((id) => node(id))}</div>

      <Wires id="ring" geometry={geometry} routing="spoke" />
    </div>
  )
}
