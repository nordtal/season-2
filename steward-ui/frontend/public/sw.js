// Steward's service worker (steward/98, concept §10c) - the traffic light on a phone's lock screen.
//
// PLAIN JAVASCRIPT, NOT TYPESCRIPT, AND DELIBERATELY OUTSIDE src/. Vite does not process anything
// under public/ - it copies it byte for byte to the built site's root, which is what a service
// worker needs anyway: the browser fetches /sw.js itself, before any bundle has run, and it has to
// be a file the browser can execute directly rather than something that only makes sense after a
// build step notices it.
//
// Holds no state of its own and reads nothing out of IndexedDB: every push already carries the
// four fields it needs (type, level, subject, path), which is the whole of what AlertWatch's
// payload is.

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
//
// SECOND LINE, NOT THE TITLE, since Till's addendum of 2026-09-19. What stood here before was
// "Steward: needs a look" in the title with the service underneath, which said the sender three
// times over - once in the title, once in the source line the browser draws itself, and the thing
// that actually happened only in the third. The title now names the subject and what is up with it,
// and this is the line below it.
const STATES = {
  ok: "all clear",
  warn: "needs a look",
  down: "down",
}

/**
 * The title: the service and what is up with it, in that order.
 *
 * Till, 2026-09-19, gave two shapes - `<service>: <action>` over `by <user>` for something somebody
 * set off, and `<service> needs attention` over `<status>` for something that happened by itself.
 * Every push this service sends is the second kind: the traffic light changes because the stack
 * changed, and no push is ever the consequence of a tap. The first shape is therefore not built
 * here rather than built and never reached - the day a run reports itself, its payload will carry
 * the person who started it, and that is the moment to write it.
 */
function titleOf(subject, level) {
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
  const subject = typeof data.subject === "string" ? data.subject : ""
  const path = typeof data.path === "string" && data.path ? data.path : "/"

  event.waitUntil(
    self.registration.showNotification(titleOf(subject, level), {
      body: STATES[level] || level,
      icon: "/icon.png",
      badge: "/icon.png",
      // One notification PER TYPE replaces the last of that type rather than stacking: each type
      // has exactly one current state, and three lock-screen entries saying "down", "ok", "down"
      // from one flapping service would be three copies of the same one fact. The type is in the
      // tag since steward/98 - before it, a full disk arriving after a stopped service silently
      // replaced it, and the service was never mentioned again.
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
