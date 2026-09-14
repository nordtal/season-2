import { Outlet, useRouterState } from "@tanstack/react-router"
import { Search as SearchIcon } from "lucide-react"

import { AppSidebar } from "@/app/app-sidebar"
import { CommandPalette } from "@/app/command-palette"
import { SignInPage } from "@/app/sign-in"
import { StewardMark } from "@/app/steward-mark"
import { ApiError } from "@/lib/api"
import { shortcutLabel } from "@/lib/keys"
import { useMe } from "@/lib/queries"
import { useIsMobile } from "@/hooks/use-mobile"
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb"
import { Failure } from "@/components/steward/query-state"
import { ScrollArea } from "@/components/ui/scroll-area"
import { SidebarInset, SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar"
import { Toaster } from "@/components/ui/sonner"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * The shell: a fixed viewport, one scrolling column, and a sidebar that is a column on a desktop
 * and a sheet on a phone.
 *
 * The document itself does not scroll. An operator watching a log window and a service table at
 * the same time should not lose the header to do it, so the header is pinned and only the content
 * column moves. That is also what makes this survive being added to a home screen: `h-svh` is the
 * *small* viewport height, so nothing is hidden behind a toolbar that has not retracted yet.
 *
 * **The safe areas are given back here, at the edges.** `index.html` asks for `viewport-fit=cover`
 * and a translucent status bar, which means iOS draws this behind the notch and the home indicator
 * rather than letterboxing it; every edge that touches one puts the inset back with
 * `env(safe-area-inset-*)`. On anything that is not a phone those are zero and none of it applies.
 */
export function Shell() {
  const me = useMe()
  const isMobile = useIsMobile()

  // Nothing is drawn until this has answered. A shell rendered first and replaced a moment later
  // would flash a sidebar full of pages that every answer 401 - which reads as a broken interface
  // rather than as a missing session.
  if (me.isPending) return <SignInPage loading />

  // A 401 here IS the signed-out state; `useMe` is the one route that answers without a session.
  // Anything else that fails is a fault, and it must not be dressed up as one: a 500 or a 429 from
  // `/api/me` used to draw the sign-in page, where the only offered action - signing in again -
  // cannot fix it, and the actual reason was nowhere on screen.
  const signedOut = me.data ? !me.data.signedIn : me.error instanceof ApiError && me.error.isSignedOut
  if (signedOut) return <SignInPage me={me.data} />

  // The shell is not drawn on a failed `/api/me` either, and not because the pages could not report
  // it themselves: the CSRF token arrives with this answer, so without it every write in the
  // interface would be refused, one confusing page at a time.
  if (!me.data) return <DoorIsStuck error={me.error} onRetry={() => void me.refetch()} />

  return (
    <TooltipProvider delayDuration={300}>
      <SidebarProvider
        // ON A DESKTOP the sidebar never collapses, so the provider's cookie and its own Ctrl+B
        // would only ever toggle a state nothing reads: `open` is held true and the setter is a
        // no-op. The phone's sheet is a different piece of state (`openMobile`) and is untouched by
        // this, which is what lets one sidebar be both things.
        open
        onOpenChange={() => undefined}
        style={{ "--sidebar-width": "13rem" } as React.CSSProperties}
      >
        <AppSidebar />
        <SidebarInset className="flex h-svh min-w-0 flex-col overflow-hidden bg-background pt-[env(safe-area-inset-top)]">
          <Header />
          <ScrollArea className="min-h-0 flex-1">
            <main className="mx-auto w-full max-w-[110rem] px-4 py-4 pb-[max(1rem,env(safe-area-inset-bottom))] md:px-6 md:py-6">
              <Outlet />
            </main>
          </ScrollArea>
        </SidebarInset>
        {/*
          Bottom right on a desktop, bottom centre on a phone - a thumb is in the middle, and a
          corner toast on a narrow screen covers whatever control is in that corner. One Toaster
          with a chosen position, not two hidden from each other: `toast()` reaches every mounted
          one, so a second would be a second notification nobody sees but the screen reader.
        */}
        <Toaster position={isMobile ? "bottom-center" : "bottom-right"} richColors closeButton />
        <CommandPalette />
      </SidebarProvider>
    </TooltipProvider>
  )
}

/**
 * `/api/me` answered, and it was not a no.
 *
 * The whole interface hangs off this one route, so there is nothing useful to draw behind this and
 * nothing to do but say what happened and offer to ask again. {@link Failure} is the same component
 * every list uses, for the same reason: it names *which* of the three services answered badly.
 */
function DoorIsStuck({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  return (
    <div className="flex min-h-svh items-center justify-center bg-background px-6 py-12">
      <div className="flex w-full max-w-md flex-col gap-6">
        <div className="flex items-center gap-3">
          <StewardMark className="size-8" />
          <div className="flex flex-col">
            <span className="text-sm font-semibold tracking-tight">Nordtal Steward</span>
            <span className="text-sm text-muted-foreground">nordtal.eu · Season 2</span>
          </div>
        </div>
        <Failure error={error} onRetry={onRetry} />
        <p className="text-center text-sm text-muted-foreground">
          This is not an expired session. You stay signed in - the interface only knows who you are
          again once this request gets through.
        </p>
      </div>
    </div>
  )
}

function Header() {
  const crumbs = useRouterState({ select: (state) => breadcrumbsFor(state.location.pathname) })

  return (
    <header className="flex h-14 shrink-0 items-center gap-2 border-b border-border px-4 md:gap-4 md:px-6">
      {/* The only way back to the navigation on a phone. Nothing renders it on a desktop, where
          the sidebar is always standing there. */}
      <SidebarTrigger className="-ml-1 size-control shrink-0 md:hidden" />
      {/*
        ON A PHONE ONLY THE LAST CRUMB IS SHOWN. `BreadcrumbList` wraps, and "Status > Configuration
        > steward-worker > steward-worker.yml" is two lines of a header that is one line tall - so
        the trail spilled over the hamburger and under the border. The trail is a convenience on a
        wide screen and the sheet is the way back on a narrow one, so below `sm` this prints where
        you are and nothing else. `flex-nowrap` is the belt to that brace: a two-crumb path that
        still does not fit now truncates rather than growing the header.
      */}
      <Breadcrumb className="min-w-0">
        <BreadcrumbList className="flex-nowrap">
          {crumbs.map((crumb, index) => {
            const last = index === crumbs.length - 1
            return (
              <BreadcrumbItem key={crumb.href} className={last ? "min-w-0" : "max-sm:hidden"}>
                {last ? (
                  <BreadcrumbPage className="truncate">{crumb.label}</BreadcrumbPage>
                ) : (
                  <>
                    <BreadcrumbLink
                      href={crumb.href}
                      className="truncate transition-colors duration-150 ease-out hover:text-primary"
                    >
                      {crumb.label}
                    </BreadcrumbLink>
                    <BreadcrumbSeparator />
                  </>
                )}
              </BreadcrumbItem>
            )
          })}
        </BreadcrumbList>
      </Breadcrumb>

      <div className="ml-auto shrink-0">
        <CommandHint />
      </div>
    </header>
  )
}

/**
 * Looks like a search field and is a button, because it is one: it does nothing but open the
 * palette, and a real input here would be a second place to type the same query.
 *
 * On a phone it is the icon alone. A 56-character-wide fake search field beside a breadcrumb is
 * most of a 390px header, and the word it would be hiding is "Search".
 *
 * The key it prints is the key the reader has: `⌘K` on a Mac, `Ctrl+K` on everything else. It used
 * to say `⌘K` here and `Ctrl` in the sidebar footer - the same shortcut, answered twice, wrongly
 * for half the readers each time.
 */
function CommandHint() {
  const open = () => {
    document.dispatchEvent(
      new KeyboardEvent("keydown", { key: "k", ctrlKey: true, bubbles: true }),
    )
  }

  return (
    <>
      <button
        type="button"
        onClick={open}
        aria-label="Search pages"
        className="flex size-control items-center justify-center rounded-md border border-border bg-card text-muted-foreground transition-colors duration-150 ease-out hover:border-input hover:text-foreground focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none active:bg-secondary sm:hidden"
      >
        <SearchIcon className="size-4" aria-hidden />
      </button>

      <button
        type="button"
        onClick={open}
        className="hidden h-control min-w-56 items-center gap-2 rounded-md border border-border bg-card px-3 text-sm text-muted-foreground transition-colors duration-150 ease-out hover:border-input hover:text-foreground focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none active:bg-secondary sm:flex"
      >
        <span>Search pages…</span>
        <kbd className="ml-auto rounded-sm border border-border bg-secondary px-1.5 py-0.5 font-mono text-[0.6875rem] text-foreground">
          {shortcutLabel("K")}
        </kbd>
      </button>
    </>
  )
}

const SECTION_LABELS: Record<string, string> = {
  services: "Services",
  operations: "Operations",
  plan: "Plan",
  runs: "Run",
  backups: "Backup",
  restore: "Restore",
  configuration: "Configuration",
  season: "Season",
  access: "Access",
  payments: "Payments",
  accounts: "Accounts",
  journal: "Journal",
  settings: "Settings",
}

/**
 * A path segment as a person should read it, or exactly as it arrived.
 *
 * `decodeURIComponent` throws on a malformed escape - `/services/%` is enough - and it is called
 * while the header renders, so the whole page became a blank screen for a URL somebody mistyped
 * or a link that lost a character. An undecodable segment is shown as it is; it is a breadcrumb,
 * not a value anything is computed from.
 */
function readable(segment: string) {
  try {
    return decodeURIComponent(segment)
  } catch {
    return segment
  }
}

function breadcrumbsFor(pathname: string) {
  const segments = pathname.split("/").filter(Boolean)
  const crumbs = [{ label: "Status", href: "/" }]
  let href = ""
  for (const segment of segments) {
    href += `/${segment}`
    crumbs.push({
      label: SECTION_LABELS[segment] ?? readable(segment),
      href,
    })
  }
  return crumbs
}
