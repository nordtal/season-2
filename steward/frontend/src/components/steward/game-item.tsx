import { useQuery } from "@tanstack/react-query"
import { useMemo } from "react"

import { type ItemDetails, type ItemLine, detailsOf, readItemData, translationsOf } from "@/lib/item-data"
import { Badge } from "@/components/ui/badge"
import { useGameData } from "@/lib/queries"
import { gameKey } from "@/lib/references"
import { t } from "@/lib/texts"
import { cn } from "@/lib/utils"

const NONE: ItemDetails = { enchantments: [], lines: [] }

/**
 * Answers what a stored item shows beyond its material: its name, enchantments and tooltip lines. A plain material,
 * an item still being read and data that does not read show nothing more than the material.
 */
export function useItemDetails(data: string): ItemDetails {
  const game = useGameData()
  const stored = useQuery({
    queryKey: ["item-data", data],
    queryFn: () => readItemData(data),
    enabled: data.trim() !== "",
    staleTime: Infinity,
    retry: false,
  })
  return useMemo(() => {
    if (stored.data === undefined) return NONE
    const registries = game.data?.registries ?? {}
    const names = (registry: string, id: string) =>
      registries[registry]?.find((entry) => entry.id === gameKey(id))?.text
    return detailsOf(stored.data, names, translationsOf(game.data))
  }, [stored.data, game.data])
}

/** The small purple badges of an item's enchantments; past `most` one badge counts the rest. */
export function EnchantmentBadges({
  enchantments,
  most,
  className,
}: {
  enchantments: string[]
  most?: number
  className?: string
}) {
  if (enchantments.length === 0) return null
  const shown = enchantments.slice(0, shownOf(enchantments.length, most))
  const rest = enchantments.length - shown.length
  const badge = "px-1.5 text-badge"
  return (
    <span className={cn("flex flex-wrap justify-center gap-1", className)}>
      {shown.map((enchantment, index) => (
        <Badge
          key={index}
          variant="outline"
          className={cn(badge, "border-enchantment/30 bg-enchantment/12 text-enchantment")}
        >
          {enchantment}
        </Badge>
      ))}
      {rest > 0 ? (
        <Badge variant="outline" className={cn(badge, "bg-secondary text-muted-foreground")}>
          {t("steward.game.more-enchantments", { count: rest })}
        </Badge>
      ) : null}
    </span>
  )
}

/** An item's lore and further details, one per line; the lore is set in italics, as the game sets it. */
export function ItemLines({ lines, className }: { lines: ItemLine[]; className?: string }) {
  if (lines.length === 0) return null
  return (
    <ul className={cn("flex flex-col gap-0.5 text-left text-xs", className)}>
      {lines.map((line, index) => (
        <li key={index} className={cn("break-words", line.lore && "italic")}>
          {line.text}
        </li>
      ))}
    </ul>
  )
}

function shownOf(count: number, most: number | undefined): number {
  return most === undefined || count <= most ? count : most - 1
}

/** The tooltip of an item: the enchantments its badges leave out past `most`, then its lines. None for a plain item. */
export function itemTip(details: ItemDetails, most?: number) {
  const hidden = details.enchantments.slice(shownOf(details.enchantments.length, most))
  if (hidden.length === 0 && details.lines.length === 0) return undefined
  return (
    <div className="flex flex-col gap-1.5">
      {hidden.length > 0 ? <EnchantmentBadges enchantments={hidden} /> : null}
      <ItemLines lines={details.lines} />
    </div>
  )
}
