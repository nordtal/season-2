import type { GameIcons } from "@/lib/api"
import { cn } from "@/lib/utils"

/** The icon sheet's cells are this many pixels square. */
const CELL = 32

/** One item's icon cut from the sheet steward-agent drew, or an empty square of the same size where it has none. */
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
