import { cn } from "@/lib/utils"

/**
 * The widths that a line of absent text may have. They are fixed per position, because a random width flickers and cannot be tested.
 */
const WIDTHS = {
  short: "w-9/20",
  medium: "w-3/5",
  long: "w-17/20",
  full: "w-full",
} as const

/**
 * It sits inside the data component's own layout, so nothing jumps. `index.css` explains the shimmer beside `@utility skeleton-shimmer`.
 */
function Skeleton({ className, width, ...props }: React.ComponentProps<"div"> & { width?: keyof typeof WIDTHS }) {
  return (
    <div
      data-slot="skeleton"
      className={cn("skeleton-shimmer rounded-md bg-muted", width ? WIDTHS[width] : undefined, className)}
      {...props}
    />
  )
}

/** It is `h-lh` tall, so it has the height of the line it replaces. */
function SkeletonText({
  width = "medium",
  className,
  ...props
}: React.ComponentProps<"span"> & { width?: keyof typeof WIDTHS }) {
  return (
    <span
      data-slot="skeleton-text"
      aria-hidden
      className={cn("flex h-lh items-center", WIDTHS[width], className)}
      {...props}
    >
      <Skeleton className="h-7/10 w-full" />
    </span>
  )
}

export { Skeleton, SkeletonText }
