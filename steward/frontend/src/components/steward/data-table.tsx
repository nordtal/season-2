import { ArrowDownIcon, ArrowUpIcon } from "@phosphor-icons/react"
import type { CSSProperties, MouseEvent, ReactNode } from "react"
import { cn } from "@/lib/utils"

import { SkeletonText } from "@/components/steward/query-state"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

/** A value's place on a phone card: a line counted from 1, and its start, its end or the whole line. */
export type Slot = { line: number; side: "start" | "end" | "wide"; labelled?: boolean }

/** `action` stands beside every line of the card; `null` leaves the value out of the card. */
export type Placement = Slot | "action" | null

export type Column<T> = {
  /** The heading, and the label of a `labelled` slot. Empty for an action column. */
  head: string
  /**
   * Takes the room the other columns leave, and wraps. Beside it every other column is as wide as its content; a table
   * without one shares its spare width out over all its columns.
   */
  grow?: boolean
  end?: boolean
  phone: Placement | ((row: T) => Placement)
  render: (row: T) => ReactNode
  /** What the cell shows before there is a row. */
  waiting?: ReactNode
  className?: string
  /** Names the order its heading picks, where the table is sortable. */
  sortKey?: string
}

/** The order of a sortable table, which the page applies before it pages. */
export type Sort = { key: string; descending: boolean }

function placement(placed: Placement): Record<string, string | number | CSSProperties | undefined> {
  if (placed === null) return { "data-phone": "off" }
  if (placed === "action") return { "data-phone": "action" }
  const style: CSSProperties & { "--phone-line": number } = { "--phone-line": placed.line }
  return {
    "data-phone": placed.side,
    "data-labelled": placed.labelled ? "" : undefined,
    style,
  }
}

function lines<T>(columns: Column<T>[]): number {
  return Math.max(1, ...columns.map((column) => (typeof column.phone === "object" ? (column.phone?.line ?? 1) : 1)))
}

/**
 * Every list in Steward. Below `--container-rows` each row is a card whose values sit in the slots their columns name.
 *
 * While `rows` is undefined it draws `waiting` rows of each column's `waiting`.
 */
export function DataTable<T>({
  columns,
  rows,
  rowKey,
  waiting = 8,
  onRowClick,
  sort,
  onSortChange,
}: {
  columns: Column<T>[]
  rows: T[] | undefined
  rowKey: (row: T) => string | number
  waiting?: number
  onRowClick?: (row: T) => void
  sort?: Sort
  onSortChange?: (sort: Sort) => void
}) {
  const cardLines: CSSProperties & { "--phone-lines": number } = { "--phone-lines": lines(columns) }
  return (
    <Table className="steward-table">
      <TableHeader>
        <TableRow>
          {columns.map((column, index) => (
            <TableHead
              key={index}
              className={cn(!column.grow && "w-px", column.end && "text-right")}
              aria-sort={
                column.sortKey !== undefined && sort?.key === column.sortKey
                  ? sort.descending
                    ? "descending"
                    : "ascending"
                  : undefined
              }
            >
              {column.sortKey !== undefined && onSortChange ? (
                <SortButton
                  head={column.head}
                  descending={sort?.key === column.sortKey ? sort.descending : undefined}
                  onClick={() =>
                    onSortChange({
                      key: column.sortKey ?? "",
                      descending: sort !== undefined && sort.key === column.sortKey ? !sort.descending : true,
                    })
                  }
                />
              ) : (
                column.head
              )}
            </TableHead>
          ))}
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows === undefined
          ? Array.from({ length: waiting }, (_, index) => (
              <TableRow key={index} style={cardLines}>
                {columns.map((column, columnIndex) => (
                  <TableCell
                    key={columnIndex}
                    data-label={column.head || undefined}
                    className={cn(column.end && "text-right")}
                    {...placement(typeof column.phone === "function" ? null : column.phone)}
                  >
                    {column.waiting ?? (column.head ? <SkeletonText width="medium" /> : null)}
                  </TableCell>
                ))}
              </TableRow>
            ))
          : rows.map((row) => (
              <TableRow
                key={rowKey(row)}
                style={cardLines}
                className={onRowClick ? "cursor-pointer" : undefined}
                onClick={onRowClick ? (event) => rowClicked(event) && onRowClick(row) : undefined}
              >
                {columns.map((column, columnIndex) => (
                  <TableCell
                    key={columnIndex}
                    data-label={column.head || undefined}
                    className={cn(column.grow && "whitespace-normal", column.end && "text-right", column.className)}
                    {...placement(typeof column.phone === "function" ? column.phone(row) : column.phone)}
                  >
                    {column.render(row)}
                  </TableCell>
                ))}
              </TableRow>
            ))}
      </TableBody>
    </Table>
  )
}

/** What a click opens on its own, so the row leaves the click to it. */
const OWN_CLICK = "a, button, input, select, textarea, label, [role=menuitem], [role=option], [role=checkbox]"

/** A tooltip's trigger, which a tap opens on a touch screen. */
const TIP = "[data-state][tabindex]"

/**
 * Whether a click belongs to the row itself. A click inside a dialog or a menu the row opened reaches the row through
 * React's tree although the dialog is elsewhere in the page, so it does not count, nor a click on a control in the row,
 * a tap that opens a tooltip, or the end of a text selection.
 */
function rowClicked(event: MouseEvent<HTMLTableRowElement>): boolean {
  const target = event.target
  const row = event.currentTarget
  if (!(target instanceof Element) || !row.contains(target)) return false
  if (window.getSelection()?.isCollapsed === false) return false
  const own = target.closest(OWN_CLICK)
  if (own !== null && row.contains(own)) return false
  const tip = target.closest(TIP)
  const touch = "pointerType" in event.nativeEvent && event.nativeEvent.pointerType === "touch"
  return !(touch && tip !== null && row.contains(tip))
}

/** A heading that sorts. Its arrow shows while the order is its own, or on hover. The first press sorts from the top. */
function SortButton({
  head,
  descending,
  onClick,
}: {
  head: string
  descending: boolean | undefined
  onClick: () => void
}) {
  const icons = { Arrow: descending === false ? ArrowUpIcon : ArrowDownIcon }
  return (
    <button
      type="button"
      onClick={onClick}
      className="group/sort -mx-1 inline-flex items-center gap-1 rounded-sm px-1 outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
    >
      {head}
      <icons.Arrow
        aria-hidden
        className={cn(
          "size-3.5 shrink-0",
          descending === undefined && "opacity-0 group-hover/sort:opacity-60 group-focus-visible/sort:opacity-60",
        )}
      />
    </button>
  )
}
