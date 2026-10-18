type Listener = (event: Event) => void

export class FakeEventSource {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSED = 2

  /** StrictMode makes two sources on mount. */
  static opened: FakeEventSource[] = []

  readonly url: string
  readonly withCredentials: boolean
  readyState: number = FakeEventSource.CONNECTING
  closeCalls = 0

  private readonly listeners = new Map<string, Listener[]>()

  constructor(url: string, init?: { withCredentials?: boolean }) {
    this.url = url
    this.withCredentials = init?.withCredentials ?? false
    FakeEventSource.opened.push(this)
  }

  addEventListener(type: string, listener: Listener): void {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener])
  }

  removeEventListener(type: string, listener: Listener): void {
    this.listeners.set(
      type,
      (this.listeners.get(type) ?? []).filter((one) => one !== listener),
    )
  }

  close(): void {
    this.readyState = FakeEventSource.CLOSED
    this.closeCalls++
  }

  emit(type: string, data?: string): void {
    if (type === "open") this.readyState = FakeEventSource.OPEN
    const event = data === undefined ? new Event(type) : new MessageEvent(type, { data })
    for (const listener of this.listeners.get(type) ?? []) listener(event)
  }

  fail(readyState: number): void {
    this.readyState = readyState
    this.emit("error")
  }
}
