import { StrictMode } from "react"
import { createRoot } from "react-dom/client"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider } from "@tanstack/react-router"

import { trackAppFrame } from "@/lib/app-frame"
import { registerServiceWorker } from "@/lib/push"
import { loadTexts } from "@/lib/texts"
import { router } from "@/router"
import "@/index.css"

/** One QueryClient for the process, so retry and staleness are decided once. */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      /** A dashboard that is read constantly must not refetch on every focus change. */
      staleTime: 5_000,
      refetchOnWindowFocus: false,
      retry: 1,
    },
  },
})

/**
 * Tracks the window height and iOS's blur band before the first render. A throw here would be a black screen, so it is guarded.
 */
try {
  trackAppFrame()
} catch (error) {
  console.error("trackAppFrame failed to start; the layout will use its CSS fallback instead", error)
}

/**
 * Registered at once, because a service worker needs no permission. A browser without one skips this.
 */
registerServiceWorker().catch((error: unknown) => {
  console.error("the service worker did not register; web push will not be available", error)
})

const container = document.getElementById("root")
if (!container) throw new Error("#root is missing from index.html")

/** Drawn once the texts are read, so an admin's override never flashes the packaged text first. */
void loadTexts().finally(() => {
  createRoot(container).render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </StrictMode>,
  )
})
