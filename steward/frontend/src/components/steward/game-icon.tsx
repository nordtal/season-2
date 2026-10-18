import type { GameIcons } from "@/lib/api"
import { cn } from "@/lib/utils"

/** The width and height in pixels of each cell of the icon sheet. */
const CELL = 32

/**
 * Draws the icon of one item, cut from the sheet that steward-agent drew. It draws an empty square of the same size when the item has none.
 */
export function GameIcon({
  icons,
  item,
  size = 24,
  className,
}: {
  icons: GameIcons | undefined
  item: string | undefined
  size?: number
  className?: string
}) {
  const slot = icons && item !== undefined ? icons.slots[item] : undefined
  const scale = size / CELL
  return (
    <span
      aria-hidden
      data-icon={slot === undefined ? undefined : item}
      className={cn("inline-block shrink-0 [image-rendering:pixelated]", className)}
      style={{
        width: size,
        height: size,
        ...(icons && slot !== undefined
          ? {
              backgroundImage: `url(${icons.url})`,
              backgroundSize: `${icons.columns * CELL * scale}px auto`,
              backgroundPosition: `-${(slot % icons.columns) * size}px -${Math.floor(slot / icons.columns) * size}px`,
            }
          : {}),
      }}
    />
  )
}
