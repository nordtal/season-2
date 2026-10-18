import { cn } from "cn"

/**
 * The widths that a line of absent text may have. They are fixed per position, because a random width flickers and cannot be tested.
 */
const WIDTHS = {
  short: "w-[45%]",
  medium: "w-[60%]",
  long: "w-[85%]",
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

/** It is `h-[1lh]` tall, so it has the height of the line it replaces. */
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
