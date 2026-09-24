import { Link, useRouterState } from "@tanstack/react-router"
import { useEffect } from "react"

import { AppFrame } from "@/app/frames"
import { FrameA } from "@/app/designs/sidebar-a"
import { FrameB } from "@/app/designs/sidebar-b"
import { FrameC } from "@/app/designs/sidebar-c"
import { FrameD } from "@/app/designs/sidebar-d"
import { initialNav, isGalleryFrame, sidebarVariant } from "@/app/designs/sidebar-variant"
import type { Me } from "@/lib/api"
import { useSidebar } from "@/components/ui/sidebar"

/**
 * The live frame, or one of the proposed sidebars while they are being compared.
 *
 * Temporary by design, like the header round's `?shell=` was: when a direction is picked it becomes
 * `AppFrame` and this file goes. See `sidebar-variant.ts`.
 */
export function ChosenFrame({ me }: { me: Me }) {
  const variant = sidebarVariant()
  const { setOpenMobile } = useSidebar()
  const onGallery = useRouterState({ select: (state) => state.location.pathname === "/designs/sidebar" })

  // A gallery frame asks for its starting state in the address. The desktop half of that is the
  // provider's `defaultOpen`; the phone's sheet has state of its own and is set here, once.
  useEffect(() => {
    if (initialNav() === "open" && window.innerWidth < 768) setOpenMobile(true)
  }, [setOpenMobile])

  const frame =
    variant === "a" ? (
      <FrameA me={me} />
    ) : variant === "b" ? (
      <FrameB me={me} />
    ) : variant === "c" ? (
      <FrameC me={me} />
    ) : variant === "d" ? (
      <FrameD me={me} />
    ) : (
      <AppFrame me={me} />
    )

  return (
    <>
      {frame}
      {variant && !isGalleryFrame() && !onGallery ? (
        // The way back to the comparison, at the one edge no direction uses: halfway down the right.
        <Link
          to="/designs/sidebar"
          aria-label={`Sidebar proposal ${variant.toUpperCase()}, back to the comparison`}
          className="fixed top-1/2 right-0 z-[60] flex h-10 w-6 -translate-y-1/2 items-center justify-center rounded-l-md border border-r-0 border-border bg-card font-mono text-xs text-muted-foreground shadow-sm hover:text-foreground"
        >
          {variant.toUpperCase()}
        </Link>
      ) : null}
    </>
  )
}
