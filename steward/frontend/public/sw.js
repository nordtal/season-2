// Steward's service worker: its alerts on a phone's lock screen.
//
// Plain JavaScript under public/, since the browser fetches /sw.js before any bundle runs and Vite copies it as is.

self.addEventListener("install", () => {
  // Takes over at once, not from the next reload.
  self.skipWaiting()
})

self.addEventListener("activate", (event) => {
  event.waitUntil(self.clients.claim())
})

self.addEventListener("push", (event) => {
  let data = {}
  try {
    data = event.data ? event.data.json() : {}
  } catch {
    data = {}
  }
  const type = typeof data.type === "string" && data.type ? data.type : "alert"
  const level = typeof data.level === "string" ? data.level : "warn"
  // Steward renders both lines from the admin bundle; a lock screen renders nothing itself.
  const title = typeof data.title === "string" && data.title ? data.title : "Steward"
  const body = typeof data.body === "string" ? data.body : level
  const path = typeof data.path === "string" && data.path ? data.path : "/"

  event.waitUntil(
    self.registration.showNotification(title, {
      body,
      icon: "/icon.png",
      badge: "/icon.png",
      // One notification per type, replacing the last of that type, so a flapping service does not stack.
      tag: `steward-alert-${type}`,
      renotify: true,
      data: { path, type },
    }),
  )
})

self.addEventListener("notificationclick", (event) => {
  event.notification.close()
  const path = (event.notification.data && event.notification.data.path) || "/"
  const target = new URL(path, self.registration.scope).href

  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((all) => {
      for (const client of all) {
        if (client.url === target && "focus" in client) return client.focus()
      }
      for (const client of all) {
        if ("focus" in client && "navigate" in client) {
          return client.focus().then(() => client.navigate(target))
        }
      }
      if (self.clients.openWindow) return self.clients.openWindow(target)
      return undefined
    }),
  )
})
