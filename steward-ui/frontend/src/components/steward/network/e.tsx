import { useRef, useState } from "react"

import { useNetwork } from "./data"
import { LAYERS } from "./layers"
import { ServiceNode } from "./node"
import { INGRESS, type NodeId } from "./topology"
import { Wires, useNodeBoxes } from "./wires"

/**
 * Draft **e** - focus.
 *
 * Same four bands `b` and `d` draw, and a different bet about what makes nine lines readable: not a
 * label, not a colour, but simply never showing all of them at once. At rest every line is drawn at
 * a fifth of its usual strength - present, but not competing with anything - and hovering, focusing
 * or tapping a box brings only *its* lines to full colour and dims every other line further still.
 * Reading the picture becomes reading it one box at a time, which is the only way nine boxes and
 * fifteen lines were ever going to share one screen without becoming a tangle.
 *
 * <h2>Hover has no phone equivalent, so tapping is not a bonus</h2>
 * A box is wrapped in something focusable and clickable, not just hoverable: `onMouseEnter` for a
 * pointer, `onFocus` for a keyboard (tabbing through the boxes previews each one's lines for free),
 * and `onClick` **pinning** the node rather than toggling the same state hover uses. A real pointer
 * hovers before it clicks, so a click that toggled "current === id ? null : id" against the
 * hover-set value would immediately cancel itself - the first version of this draft did exactly
 * that, and a screenshot taken by clicking a node came back showing the resting state, having
 * hovered the node on the way to the click and then un-set it again on arrival. Two independent
 * bits of state - `hovered` and `pinned` - are what a tap needs to survive its own hover.
 *
 * <h2>The honest gap</h2>
 * The wrapper that makes a box hoverable and tappable is a second focusable element sitting around
 * the identifier link `ServiceNode` already draws - two tab stops for one box, where a shipped
 * version needs one. It is left as the ticket's honestly-named flaw for this draft rather than
 * solved here, because the real fix (folding the activation into `ServiceNode` itself) is a
 * decision about every draft's shared node, not about this one alone.
 */
export function FocusDraft() {
  const host = useRef<HTMLDivElement>(null)
  const geometry = useNodeBoxes(host)
  const network = useNetwork()
  const [hovered, setHovered] = useState<NodeId | null>(null)
  const [pinned, setPinned] = useState<NodeId | null>(null)
  const active = hovered ?? pinned

  const node = (id: NodeId) => {
    const box = (
      <ServiceNode key={id} id={id} service={network.service(id)} players={network.players(id)} />
    )
    if (id === INGRESS) return box
    return (
      <div
        key={id}
        role="button"
        tabIndex={0}
        aria-label={`focus ${id}`}
        aria-pressed={pinned === id}
        className="rounded-md outline-none focus-visible:ring-2 focus-visible:ring-ring"
        onMouseEnter={() => setHovered(id)}
        onMouseLeave={() => setHovered((current) => (current === id ? null : current))}
        onFocus={() => setHovered(id)}
        onBlur={() => setHovered((current) => (current === id ? null : current))}
        onClick={() => setPinned((current) => (current === id ? null : id))}
        onKeyDown={(event) => {
          if (event.key !== "Enter" && event.key !== " ") return
          event.preventDefault()
          setPinned((current) => (current === id ? null : id))
        }}
      >
        {box}
      </div>
    )
  }

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
            {layer.members.map((id) => node(id))}
          </div>
        </div>
      ))}

      <Wires id="e" geometry={geometry} focusable active={active} />
    </div>
  )
}
