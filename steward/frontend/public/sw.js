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

// The line below the title: each level in words, read once on a lock screen.
const STATES = {
  ok: "all clear",
  warn: "needs a look",
  down: "down",
}

/** The title steward wrote, or one made of the subject and its level for a push that carries none. */
function titleOf(title, subject, level) {
  if (title) return title
  if (!subject) return level === "ok" ? "Steward is clear" : "Steward needs attention"
  return level === "ok" ? `${subject} is clear` : `${subject} needs attention`
}

self.addEventListener("push", (event) => {
  let data = {}
  try {
    data = event.data ? event.data.json() : {}
  } catch {
    data = {}
  }
  const type = typeof data.type === "string" && data.type ? data.type : "alert"
  const level = typeof data.level === "string" ? data.level : "warn"
  const title = typeof data.title === "string" ? data.title : ""
  const subject = typeof data.subject === "string" ? data.subject : ""
  const path = typeof data.path === "string" && data.path ? data.path : "/"

  event.waitUntil(
    self.registration.showNotification(titleOf(title, subject, level), {
      body: STATES[level] || level,
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
