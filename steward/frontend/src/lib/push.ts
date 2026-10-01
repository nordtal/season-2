/** Whether this browser can subscribe to push at all; Safari needs 16.4. */
export function pushSupported(): boolean {
  return (
    typeof window !== "undefined" && "serviceWorker" in navigator && "PushManager" in window && "Notification" in window
  )
}

/** Registers `/sw.js`, safe to call early since registering asks for no permission. */
export async function registerServiceWorker(): Promise<ServiceWorkerRegistration | null> {
  if (!("serviceWorker" in navigator)) return null
  return navigator.serviceWorker.register("/sw.js")
}

/** The VAPID public key from `/api/web-push/public-key` as the `Uint8Array` `applicationServerKey` wants. */
export function urlBase64ToUint8Array(base64Url: string): Uint8Array<ArrayBuffer> {
  const padding = "=".repeat((4 - (base64Url.length % 4)) % 4)
  const base64 = (base64Url + padding).replace(/-/g, "+").replace(/_/g, "/")
  const raw = atob(base64)
  const bytes = new Uint8Array(raw.length)
  for (let i = 0; i < raw.length; i += 1) bytes[i] = raw.charCodeAt(i)
  return bytes
}

/**
 * Subscribes this browser, called straight from the button's `onClick`.
 *
 * iOS only asks for permission inside a genuine tap, so the key is passed in rather than awaited.
 */
export async function subscribeToPush(publicKey: string): Promise<PushSubscriptionJSON> {
  const registration = await navigator.serviceWorker.ready
  const subscription = await registration.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: urlBase64ToUint8Array(publicKey),
  })
  return subscription.toJSON()
}

/** Unsubscribes this browser from the push service and returns the endpoint for Steward's own table. */
export async function unsubscribeFromPush(): Promise<string | null> {
  if (!("serviceWorker" in navigator)) return null
  const registration = await navigator.serviceWorker.ready
  const subscription = await registration.pushManager.getSubscription()
  if (!subscription) return null
  const endpoint = subscription.endpoint
  await subscription.unsubscribe()
  return endpoint
}

/** The endpoint this browser is subscribed with, or null, for the button's own state. */
export async function currentPushEndpoint(): Promise<string | null> {
  if (!("serviceWorker" in navigator)) return null
  const registration = await navigator.serviceWorker.getRegistration()
  if (!registration) return null
  const subscription = await registration.pushManager.getSubscription()
  return subscription ? subscription.endpoint : null
}
