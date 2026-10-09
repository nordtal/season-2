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

describe("PaymentsPage - one column for each field", () => {
  const PAID_PAYMENT = {
    id: "p2",
    reference: "QX7Z2M",
    discordId: "214906139328839681",
    days: 90,
    amountCents: 1300,
    donationCents: 200,
    status: "PAID",
    shareUrl: "https://bunq.me/abc",
    created: "2026-09-20T17:45:00Z",
    expires: "2026-10-04T17:45:00Z",
    settled: "2026-09-21T09:03:00Z",
  }

  it("keeps Paid as a column of its own, with no copy under the status", async () => {
    vi.stubGlobal("fetch", backend({ payments: () => [PAID_PAYMENT] }))
    draw(<PaymentsPage />)

    const row = await rowFor("QX7Z2M")
    const paidCell = assertElement(row.querySelector('td[data-label="Paid"]'), "the Paid cell")
    expect(assertElement(paidCell.querySelector("time"), "the paid time").getAttribute("datetime")).toBe(
      "2026-09-21T09:03:00Z",
    )
    const statusCell = assertElement(row.querySelector('td[data-label="Status"]'), "the Status cell")
    expect(statusCell.querySelector("time")).toBeNull()
  })

  it("shows the date over the clock, so Deadline stays narrow", async () => {
    vi.stubGlobal("fetch", backend({ payments: () => [PAID_PAYMENT] }))
    draw(<PaymentsPage />)

    const row = await rowFor("QX7Z2M")
    const deadline = assertElement(row.querySelector('td[data-label="Deadline"] time'), "the deadline")
    expect(deadline.children).toHaveLength(2)
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

describe("PaymentsPage - the figures wrap cleanly", () => {
  it("draws Open, Paid and Requested with no separator to hang at a line's end and no sentence under them", async () => {
    vi.stubGlobal("fetch", backend({ payments: () => [] }))
    draw(<PaymentsPage />)

    const figures = assertElement(
      (await screen.findByText("Requested (paid requests)")).closest("div")?.parentElement ?? null,
      "the figures row",
    )
    expect(figures.querySelector("[data-slot='separator']")).toBeNull()
    expect(figures.querySelector("p")).toBeNull()
  })
})
