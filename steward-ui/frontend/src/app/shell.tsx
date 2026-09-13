import { Outlet, useRouterState } from "@tanstack/react-router"

import { AppSidebar } from "@/app/app-sidebar"
import { CommandPalette } from "@/app/command-palette"
import { SignInPage } from "@/app/sign-in"
import { ApiError } from "@/lib/api"
import { useMe } from "@/lib/queries"
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb"
import { ScrollArea } from "@/components/ui/scroll-area"
import { SidebarInset, SidebarProvider } from "@/components/ui/sidebar"
import { Toaster } from "@/components/ui/sonner"
import { TooltipProvider } from "@/components/ui/tooltip"

/**
 * The shell: a fixed viewport, a sidebar that never collapses and one scrolling column.
 *
 * The document itself does not scroll. An operator watching a log window and a service table at
 * the same time should not lose the header to do it, so the header is pinned and only the content
 * column moves.
 */
export function Shell() {
  const me = useMe()

  // Nothing is drawn until this has answered. A shell rendered first and replaced a moment later
  // would flash a sidebar full of pages that every answer 401 - which reads as a broken interface
  // rather than as a missing session.
  if (me.isPending) return <SignInPage loading />

  // A 401 here IS the signed-out state; `useMe` is the one route that answers without a session,
  // so anything else failing is a real fault and is left to the pages to report.
  const signedOut = me.data ? !me.data.signedIn : me.error instanceof ApiError
  if (signedOut) return <SignInPage me={me.data} />

  return (
    <TooltipProvider delayDuration={300}>
      <SidebarProvider
        // The sidebar never collapses, so the provider's cookie and keyboard shortcut would only
        // ever toggle a state nothing reads. Fixed open, and the width is stated once here.
        open
        onOpenChange={() => undefined}
        style={{ "--sidebar-width": "15rem" } as React.CSSProperties}
      >
        <AppSidebar />
        <SidebarInset className="flex h-svh min-w-0 flex-col overflow-hidden bg-background">
          <Header />
          <ScrollArea className="min-h-0 flex-1">
            <main className="mx-auto w-full max-w-[110rem] px-6 py-6">
              <Outlet />
            </main>
          </ScrollArea>
        </SidebarInset>
        <Toaster position="bottom-right" richColors closeButton />
        <CommandPalette />
      </SidebarProvider>
    </TooltipProvider>
  )
}

function Header() {
  const crumbs = useRouterState({ select: (state) => breadcrumbsFor(state.location.pathname) })

  return (
    <header className="flex h-14 shrink-0 items-center gap-4 border-b border-border px-6">
      <Breadcrumb className="min-w-0">
        <BreadcrumbList>
          {crumbs.map((crumb, index) => {
            const last = index === crumbs.length - 1
            return (
              <BreadcrumbItem key={crumb.href}>
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

      <div className="ml-auto">
        <CommandHint />
      </div>
    </header>
  )
}

/**
 * Looks like a search field and is a button, because it is one: it does nothing but open the
 * palette, and a real input here would be a second place to type the same query.
 */
function CommandHint() {
  return (
    <button
      type="button"
      onClick={() => {
        document.dispatchEvent(
          new KeyboardEvent("keydown", { key: "k", ctrlKey: true, bubbles: true }),
        )
      }}
      className="flex h-control min-w-56 items-center gap-2 rounded-md border border-border bg-card px-3 text-sm text-muted-foreground transition-colors duration-150 ease-out hover:border-input hover:text-foreground focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none active:bg-secondary"
    >
      <span>Seite suchen…</span>
      <kbd className="ml-auto rounded-sm border border-border bg-secondary px-1.5 py-0.5 font-mono text-[0.6875rem] text-foreground">
        ⌘K
      </kbd>
    </button>
  )
}

const SECTION_LABELS: Record<string, string> = {
  dienste: "Dienste",
  betrieb: "Betrieb",
  plan: "Plan",
  lauf: "Lauf",
  sicherung: "Sicherung",
  wiederherstellen: "Wiederherstellen",
  konfiguration: "Konfiguration",
  saison: "Saison",
  zugaenge: "Zugänge",
  zahlungen: "Zahlungen",
  konten: "Konten",
  journal: "Journal",
  einstellungen: "Einstellungen",
}

/**
 * A path segment as a person should read it, or exactly as it arrived.
 *
 * `decodeURIComponent` throws on a malformed escape - `/dienste/%` is enough - and it is called
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
  const crumbs = [{ label: "Zustand", href: "/" }]
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
