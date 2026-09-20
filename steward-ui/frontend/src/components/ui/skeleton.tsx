import { cn } from "cn"

/**
 * The widths a line of absent text is allowed to have (steward/120).
 *
 * Three of them, picked per position and never at random: a random width flickers on every
 * re-render and no test can hold it. They are shares rather than character counts because the
 * thing underneath is a share too - a name column is a name column at 390px and at 1440.
 */
const WIDTHS = {
  short: "w-[45%]",
  medium: "w-[60%]",
  long: "w-[85%]",
  full: "w-full",
} as const

/**
 * A surface standing in for something that is not here yet.
 *
 * It shimmers rather than pulses (steward/120, Till: *"Probiere gerne Schimmer"*); the animation,
 * its timing and why it is a moved pseudo-element rather than a moved background are argued at
 * length beside `@utility skeleton-shimmer` in `index.css`.
 *
 * **It is never the whole answer.** A skeleton that does not resemble what replaces it jumps when
 * the data lands, which is worse than an empty box - so the rule this file serves is that a data
 * component draws its own layout with these inside it, rather than a separate skeleton component
 * being kept in step by hand.
 */
function Skeleton({
  className,
  width,
  ...props
}: React.ComponentProps<"div"> & { width?: keyof typeof WIDTHS }) {
  return (
    <div
      data-slot="skeleton"
      className={cn(
        "skeleton-shimmer rounded-md bg-muted",
        width ? WIDTHS[width] : undefined,
        className
      )}
      {...props}
    />
  )
}

/**
 * A line of text that has not arrived, at the height of the text it replaces.
 *
 * `h-[1lh]` rather than a fixed height: it takes the line height of wherever it is dropped, so a
 * `text-xs` caption and a `text-lg` heading each get a bar the size of their own line without the
 * call site repeating the type scale. The inner bar is what carries the width, so the outer box
 * still occupies the full line and nothing reflows when the words appear.
 */
function SkeletonText({
  width = "medium",
  className,
  ...props
}: React.ComponentProps<"span"> & { width?: keyof typeof WIDTHS }) {
  return (
    <span
      data-slot="skeleton-text"
      aria-hidden
      className={cn("flex h-[1lh] items-center", WIDTHS[width], className)}
      {...props}
    >
      <Skeleton className="h-[0.7lh] w-full" />
    </span>
  )
}

export { Skeleton, SkeletonText }
