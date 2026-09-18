// Steward's service worker (steward/98, concept §10c) - the traffic light on a phone's lock screen.
//
// PLAIN JAVASCRIPT, NOT TYPESCRIPT, AND DELIBERATELY OUTSIDE src/. Vite does not process anything
// under public/ - it copies it byte for byte to the built site's root, which is what a service
// worker needs anyway: the browser fetches /sw.js itself, before any bundle has run, and it has to
// be a file the browser can execute directly rather than something that only makes sense after a
// build step notices it.
//
// Holds no state of its own and reads nothing out of IndexedDB: every push already carries the
// three fields it needs (level, subject, path), which is the whole of what AlertWatch's payload is.

self.addEventListener("install", () => {
  // Takes over from the moment it is installed, not from the next full reload. There is nothing in
  // an older version of this file worth finishing first.
  self.skipWaiting()
})

self.addEventListener("activate", (event) => {
  event.waitUntil(self.clients.claim())
})

// What the three level values are called on a lock screen. Deliberately not the level's own word
// alone ("warn") - a notification is read once, standing up, without the rest of the page beside it.
const TITLES = {
  ok: "Steward: all clear",
  warn: "Steward: needs a look",
  down: "Steward: down",
}

self.addEventListener("push", (event) => {
  let data = {}
  try {
    data = event.data ? event.data.json() : {}
  } catch {
    data = {}
  }
  const level = typeof data.level === "string" ? data.level : "warn"
  const subject = typeof data.subject === "string" ? data.subject : ""
  const path = typeof data.path === "string" && data.path ? data.path : "/"

  event.waitUntil(
    self.registration.showNotification(TITLES[level] || "Steward", {
      body: subject,
      icon: "/icon.png",
      badge: "/icon.png",
      // One notification replaces the last rather than stacking: the traffic light has exactly one
      // current state, and three lock-screen entries saying "down", "ok", "down" from one flapping
      // service would be three copies of the same one fact.
      tag: "steward-alert-level",
      renotify: true,
      data: { path },
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
