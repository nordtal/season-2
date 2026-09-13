import { StrictMode } from "react"
import { createRoot } from "react-dom/client"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { RouterProvider } from "@tanstack/react-router"

import { router } from "@/router"
import "@/index.css"

/**
 * One QueryClient for the process. Nothing fetches yet - the client is wired now so that the first
 * page that does has somewhere to put its cache, and so that the retry/staleness policy is a
 * decision made once rather than per call site.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // An operator's dashboard is read constantly and must not fight the network on every focus
      // change; the pages that need to be live will subscribe rather than poll harder.
      staleTime: 5_000,
      refetchOnWindowFocus: false,
      retry: 1,
    },
  },
})

const container = document.getElementById("root")
if (!container) throw new Error("#root is missing from index.html")

createRoot(container).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)
