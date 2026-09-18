import { Outlet } from "@tanstack/react-router"

import { AppSidebar } from "@/app/app-sidebar"
import { useCrumbs } from "@/app/breadcrumbs"
import { Island, SearchButton } from "@/app/island"
import { UserMenu } from "@/app/user-menu"
import type { Me } from "@/lib/api"
import { ScrollArea } from "@/components/ui/scroll-area"
import { SidebarInset, useSidebar } from "@/components/ui/sidebar"

/**
 * The frame everything signed-in is drawn inside (steward/89).
 *
 * <h2>Nine shells stood here, and eight of them are gone</h2>
 * Till asked on 2026-09-17 for the header to go and for an island at the top left to carry what it
 * held; nine live shells were built so he could hold a phone and compare them, reachable with
 * `?shell=a` … `?shell=i`. He picked `f` - the one in which the island and the head of the
 * expanded sidebar are the *same* growing element rather than two things swapping places - and
 * named four corrections. The eight he did not pick are deleted rather than kept behind a query
 * parameter: a comparison that is over is a fork in the code with nobody standing at it, and every
 * one of those shells had its own answer to where the search goes, which is eight ways to get the
 * one remaining answer wrong. What each of them tried is in the ticket, which is where a rejected
 * design belongs.
 *
 * The four corrections are all in {@link Island} and in the head of {@link AppSidebar}, because
 * that is where they were: no border under the grown island, a head no taller than the island
 * standing in it, no breadcrumbs while the column is open, and a right edge that meets the
 * column's border instead of lying on top of it.
 *
 * <h2>What is fixed and what scrolls</h2>
 * The document itself never scrolls. The island and the account picture are fixed to the top
 * corners, the sidebar is a column on a machine with a pointer and a sheet on a phone, and only
 * {@link Content} moves - so an operator watching a log window and a service table at the same
 * time does not lose their place to do it.
 *
 * <h2>Where the search is</h2>
 * Top right, beside the account, in the same small surface. A phone has no `⌘K`, so a frame with
 * nothing to tap has no command palette at all; top right is where the old header kept it, which
 * is the one position a reader of this interface already knows.
 */
export function AppFrame({ me }: { me: Me }) {
  const crumbs = useCrumbs()
  const { open, isMobile, toggleSidebar } = useSidebar()
  // On a phone the navigation is a sheet lying over everything, not a column standing beside it,
  // so the island has nothing to grow into and stays a pill however open the sheet is.
  const expanded = open && !isMobile

  return (
    <>
      <AppSidebar />
      <SidebarInset className="flex h-(--app-height) min-w-0 flex-col overflow-hidden bg-background pt-[env(safe-area-inset-top)]">
        <Island crumbs={crumbs} expanded={expanded} onToggle={toggleSidebar} />
        {/*
          The other corner. `pointer-events-none` on the strip and `auto` on the surface inside it:
          it spans the whole width to be able to sit at the right edge, and must not swallow clicks
          on the page over the space it draws nothing in.
        */}
        <div className="pointer-events-none fixed inset-x-0 top-0 z-30 flex justify-end px-4 pt-(--island-top) md:px-6">
          <div className="pointer-events-auto flex shrink-0 items-center gap-1 rounded-lg border border-border bg-card p-1">
            <SearchButton plain />
            <UserMenu me={me} plain />
          </div>
        </div>
        <Content />
      </SidebarInset>
    </>
  )
}

/**
 * The one scrolling column.
 *
 * `pt-(--island-clearance)` is what keeps the first line of a page out from under the island that
 * floats over it - the same variable the sidebar's head uses, so the two cannot drift apart again.
 */
function Content() {
  return (
    <ScrollArea className="min-h-0 flex-1">
      <main className="mx-auto w-full max-w-[110rem] px-4 pt-(--island-clearance) pb-[max(1rem,env(safe-area-inset-bottom))] md:px-6">
        <Outlet />
      </main>
    </ScrollArea>
  )
}
