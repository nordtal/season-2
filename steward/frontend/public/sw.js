// Steward's service worker. It stays plain JavaScript in public/, because the browser fetches /sw.js before any bundle runs.

self.addEventListener("install", () => {
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
  // The admin bundle renders both lines of an alert. The lock screen renders none.
  const title = typeof data.title === "string" && data.title ? data.title : "Steward"
  const body = typeof data.body === "string" ? data.body : level
  const path = typeof data.path === "string" && data.path ? data.path : "/"

  event.waitUntil(
    self.registration.showNotification(title, {
      body,
      icon: "/icon.png",
      badge: "/icon.png",
      // A notification replaces the last one of the same type, so a flapping service shows one notification.
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
