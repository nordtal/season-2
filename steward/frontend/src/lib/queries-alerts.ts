import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import {
  api,
  type PageSettings,
  type AlertPreference,
  type AlertType,
  type PushDevice,
  type AlertPreferences,
  type Alerts,
  type WebPushPublicKey,
} from "@/lib/api"
import { live } from "@/lib/live"
import { currentPushEndpoint, subscribeToPush, unsubscribeFromPush } from "@/lib/push"
import { SECOND, keys } from "@/lib/query-keys"

export function useAlerts(enabled = true) {
  return useQuery({
    queryKey: keys.alerts,
    queryFn: () => api<Alerts>("/api/alerts"),
    ...live("ALERTS"),
    enabled,
  })
}

export function useAvatarBaseUrl(enabled = true) {
  return useQuery({
    queryKey: keys.settings,
    queryFn: () => api<PageSettings>("/api/settings"),
    staleTime: 5 * 60 * SECOND,
    enabled,
    select: (settings) => settings.minecraftHeadBaseUrl,
  })
}

/**
 * The VAPID public key, fetched before any button needs it. iOS opens the permission dialog only while the tap is on the call stack.
 */
export function useWebPushPublicKey(enabled = true) {
  return useQuery({
    queryKey: keys.webPushPublicKey,
    queryFn: () => api<WebPushPublicKey>("/api/web-push/public-key"),
    staleTime: Infinity,
    retry: false,
    enabled,
  })
}

export function useWebPushSubscription(enabled = true) {
  return useQuery({
    queryKey: keys.webPushSubscription,
    queryFn: () => currentPushEndpoint(),
    staleTime: 0,
    enabled,
  })
}

export function useSubscribeWebPush() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (publicKey: string) => {
      const subscription = await subscribeToPush(publicKey)
      await api<void>("/api/web-push/subscribe", { method: "POST", body: subscription })
      return subscription
    },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.webPushSubscription })
      void client.invalidateQueries({ queryKey: keys.webPushDevices })
    },
  })
}

export function useUnsubscribeWebPush() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      const endpoint = await unsubscribeFromPush()
      if (endpoint) {
        await api<void>("/api/web-push/subscribe", { method: "DELETE", body: { endpoint } })
      }
      return endpoint
    },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.webPushSubscription })
      void client.invalidateQueries({ queryKey: keys.webPushDevices })
    },
  })
}

/**
 * When it is this browser, the push subscription goes too, or its button would still read "On".
 */
export function useForgetWebPushDevice() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (endpoint: string) => {
      if ((await currentPushEndpoint()) === endpoint) {
        await unsubscribeFromPush()
      }
      await api<void>("/api/web-push/subscribe", { method: "DELETE", body: { endpoint } })
      return endpoint
    },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.webPushSubscription })
      void client.invalidateQueries({ queryKey: keys.webPushDevices })
    },
  })
}

export function useWebPushDevices(enabled = true) {
  return useQuery({
    queryKey: keys.webPushDevices,
    queryFn: () => api<PushDevice[]>("/api/web-push/devices"),
    enabled,
  })
}

export function useAlertPreferences(enabled = true) {
  return useQuery({
    queryKey: keys.alertPreferences,
    queryFn: () => api<AlertPreferences>("/api/alerts/preferences"),
    enabled,
  })
}

/** The answer goes into the cache, so the switch does not spring back during a refetch. */
export function useSetAlertPreference() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (choice: AlertPreference) =>
      api<AlertPreference>("/api/alerts/preferences", {
        method: "PUT",
        body: choice,
      }),
    onSuccess: (saved) =>
      client.setQueryData(keys.alertPreferences, (current?: AlertPreferences) =>
        current ? { ...current, [saved.type]: { ...current[saved.type], [saved.channel]: saved.enabled } } : current,
      ),
  })
}

/**
 * A dead subscription answers 404 after the server removed its row, so the device list is refreshed either way.
 */
export function useTestWebPush() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (what: { endpoint: string; type: AlertType }) =>
      api<void>("/api/web-push/test", { method: "POST", body: what }),
    onSettled: () => {
      void client.invalidateQueries({ queryKey: keys.webPushDevices })
      void client.invalidateQueries({ queryKey: keys.webPushSubscription })
    },
  })
}
