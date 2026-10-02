import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"

import { PaymentsPage } from "@/pages/payments"
import { toast } from "sonner"
import { assertElement, backend, draw, requestBody, rowFor } from "@/pages/access.fixtures"

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe("PaymentsPage - settle as a row action", () => {
  const OPEN_PAYMENT = {
    id: "p1",
    reference: "AB12CD",
    discordId: "214906139328839681",
    days: 30,
    amountCents: 500,
    donationCents: 0,
    status: "OPEN",
    created: "2026-09-01T00:00:00Z",
    expires: "2026-09-20T00:00:00Z",
  }
  it("offers Settle on an open request", async () => {
    vi.stubGlobal("fetch", backend({ payments: () => [OPEN_PAYMENT] }))
    draw(<PaymentsPage />)

    const row = await rowFor("AB12CD")
    expect(within(row).getByRole("button", { name: /settle/i })).toBeTruthy()
  })

  it("sends the row's own reference, with no picker to get wrong", async () => {
    const fetched = backend({ payments: () => [OPEN_PAYMENT] })
    vi.stubGlobal("fetch", fetched)
    const success = vi.spyOn(toast, "success")
    draw(<PaymentsPage />)

    const row = await rowFor("AB12CD")
    fireEvent.click(within(row).getByRole("button", { name: /settle/i }))
    const dialog = await screen.findByRole("alertdialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Settle" }))

    // Booked by steward in one answer, with no bot request to wait for.
    await waitFor(() => {
      const call = fetched.mock.calls.find(([url]) => url === "/api/access/settle")
      expect(call).toBeTruthy()
    })
    const call = fetched.mock.calls.find(([url]) => url === "/api/access/settle")!
    expect(requestBody(call[1])).toEqual({ reference: "AB12CD" })
    await waitFor(() => expect(success).toHaveBeenCalledWith("AB12CD settled", expect.anything()))
    expect(fetched.mock.calls.some(([url]) => url.startsWith("/api/access/requests/"))).toBe(false)
  })

  // jsdom has no layout, so this checks the class that would cause the wrap.
  it("keeps Tab and Settle on one line instead of letting them wrap", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        payments: () => [{ ...OPEN_PAYMENT, shareUrl: "https://bunq.me/xyz" }],
      }),
    )
    draw(<PaymentsPage />)

    const tab = await screen.findByRole("link", { name: /tab/i })
    const actions = assertElement(tab.parentElement, "the actions container")
    expect(actions.className).not.toMatch(/flex-wrap/)
  })
})

/** Every Payments column is a field, so the width at 1440px is kept by folding `Created` and `Donation`. */
describe("PaymentsPage - the column budget fits the card at 1440px", () => {
  it("keeps the declared header widths under 1152px (72rem), the measured card width", async () => {
    vi.stubGlobal(
      "fetch",
      backend({
        payments: () => [
          {
            id: "p1",
            reference: "AB12CD",
            discordId: "214906139328839681",
            days: 30,
            amountCents: 500,
            donationCents: 0,
            status: "OPEN",
            created: "2026-09-01T00:00:00Z",
            expires: "2026-09-20T00:00:00Z",
          },
        ],
      }),
    )
    draw(<PaymentsPage />)

    await screen.findByText("AB12CD")
    const headers = screen.getAllByRole("columnheader")
    const remWidths = headers.map((header) => {
      const match = header.className.match(/w-\[(\d+(?:\.\d+)?)rem\]/)
      return match ? Number.parseFloat(match[1]) : 0
    })
    const total = remWidths.reduce((sum, width) => sum + width, 0)

    expect(total).toBeLessThan(72)
  })
})
