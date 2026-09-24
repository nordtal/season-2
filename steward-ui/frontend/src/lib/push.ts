/**
 * Web Push (steward/98, concept §10c): the traffic light reaching a phone's lock screen rather than
 * only the page somebody might not have open.
 *
 * <h2>Why this is not called from a `useEffect`</h2>
 * iOS only shows the permission dialog `pushManager.subscribe()` triggers while the call is still
 * inside the call stack of a genuine tap - a mount, a timer or an `await` on something unrelated
 * first can spend that "user activation" before the browser ever sees the request. That is why
 * {@link subscribeToPush} takes the public key as a plain argument rather than fetching it itself:
 * the caller (the notifications dialog's switch) reads it out of a query that already resolved before the
 * button was drawn, so the only `await` between the tap and `subscribe()` is the browser's own,
 * already-registered service worker becoming ready.
 */

/** Whether this browser can subscribe to push at all. Safari on macOS/iOS needed 16.4 for this. */
export function pushSupported(): boolean {
  return (
    typeof window !== "undefined"
    && "serviceWorker" in navigator
    && "PushManager" in window
    && "Notification" in window
  )
}

/**
 * Registers `/sw.js`. Safe to call unconditionally and early (main.tsx does): registering a service
 * worker needs no permission and asks for none, so it does not need to wait for a tap the way
 * {@link subscribeToPush} does.
 */
export async function registerServiceWorker(): Promise<ServiceWorkerRegistration | null> {
  if (!("serviceWorker" in navigator)) return null
  return navigator.serviceWorker.register("/sw.js")
}

/**
 * The VAPID public key as `/api/web-push/public-key` sends it (base64url of the uncompressed EC
 * point) turned into the `Uint8Array` `PushManager.subscribe()`'s `applicationServerKey` wants.
 */
export function urlBase64ToUint8Array(base64Url: string): Uint8Array<ArrayBuffer> {
  const padding = "=".repeat((4 - (base64Url.length % 4)) % 4)
  const base64 = (base64Url + padding).replace(/-/g, "+").replace(/_/g, "/")
  const raw = atob(base64)
  const bytes = new Uint8Array(raw.length)
  for (let i = 0; i < raw.length; i += 1) bytes[i] = raw.charCodeAt(i)
  return bytes
}

/**
 * Subscribes this browser - called directly from the button's `onClick`, see the module note on why.
 *
 * @param publicKey the VAPID public key, already fetched before the button was drawn
 * @returns the subscription in the shape `POST /api/web-push/subscribe` reads
 */
export async function subscribeToPush(publicKey: string): Promise<PushSubscriptionJSON> {
  const registration = await navigator.serviceWorker.ready
  const subscription = await registration.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: urlBase64ToUint8Array(publicKey),
  })
  return subscription.toJSON()
}

/**
 * Unsubscribes this browser, both from the push service and (by returning the endpoint) from
 * Steward's own table.
 *
 * @returns the endpoint that was unsubscribed, or null when there was none
 */
export async function unsubscribeFromPush(): Promise<string | null> {
  if (!("serviceWorker" in navigator)) return null
  const registration = await navigator.serviceWorker.ready
  const subscription = await registration.pushManager.getSubscription()
  if (!subscription) return null
  const endpoint = subscription.endpoint
  await subscription.unsubscribe()
  return endpoint
}

/** The endpoint this browser is currently subscribed with, or null - for the button's own state. */
export async function currentPushEndpoint(): Promise<string | null> {
  if (!("serviceWorker" in navigator)) return null
  const registration = await navigator.serviceWorker.getRegistration()
  if (!registration) return null
  const subscription = await registration.pushManager.getSubscription()
  return subscription ? subscription.endpoint : null
}
