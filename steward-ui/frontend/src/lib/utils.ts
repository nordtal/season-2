/**
 * `cn` now ships as shadcn's own package (github.com/shadcn-ui/cn) and every file the registry
 * generates imports it from there. This re-export exists so that a component added later - the
 * registry still emits `@/lib/utils` for some of them - resolves to the same function rather than
 * to a second copy of clsx + tailwind-merge.
 */
export { cn } from "cn"
