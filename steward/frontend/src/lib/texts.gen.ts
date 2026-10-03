/** The values each of Steward's texts takes; written by `./gradlew :steward:generateApiTypes`. */
import type { Arg } from "@/lib/texts"

export type TextArgs = {
  "alert.admin-not-kept": Record<string, never>
  "alert.claimed": {
    payment: Arg["text"]
    amount: Arg["money"]
    reference: Arg["text"]
  }
  "alert.clear": {
    subject: Arg["text"]
  }
  "alert.disk": {
    percent: Arg["number"]
  }
  "alert.dm": Record<string, never>
  "alert.docker-state": {
    state: Arg["text"]
  }
  "alert.donor-not-given": {
    person: Arg["mention"]
  }
  "alert.dump-matters": Record<string, never>
  "alert.failed-for": {
    person: Arg["mention"]
    error: Arg["text"]
  }
  "alert.health-fails": Record<string, never>
  "alert.level": {
    level: Arg["choice"]
  }
  "alert.link-failed": Record<string, never>
  "alert.link-refused": Record<string, never>
  "alert.memory": {
    percent: Arg["number"]
  }
  "alert.no-archive": Record<string, never>
  "alert.no-backup": Record<string, never>
  "alert.no-dump": Record<string, never>
  "alert.no-limit": Record<string, never>
  "alert.no-services": Record<string, never>
  "alert.no-such-role": {
    id: Arg["text"]
  }
  "alert.no-tier": {
    payment: Arg["text"]
    amount: Arg["money"]
    reference: Arg["text"]
  }
  "alert.not-compared": Record<string, never>
  "alert.not-open": {
    payment: Arg["text"]
    amount: Arg["money"]
    reference: Arg["text"]
    status: Arg["choice"]
  }
  "alert.not-running": {
    service: Arg["text"]
  }
  "alert.old-archive": {
    volume: Arg["text"]
    hours: Arg["number"]
  }
  "alert.old-dump": {
    hours: Arg["number"]
  }
  "alert.older-image": {
    services: Arg["list"]
    count: Arg["number"]
  }
  "alert.only-started": Record<string, never>
  "alert.payment": Record<string, never>
  "alert.permitted-age": {
    hours: Arg["number"]
  }
  "alert.purchase-failed": Record<string, never>
  "alert.reconcile-idle": Record<string, never>
  "alert.refused": {
    reference: Arg["text"]
    error: Arg["text"]
  }
  "alert.role-missing": {
    role: Arg["choice"]
  }
  "alert.role-not-changed": {
    role: Arg["choice"]
    given: Arg["choice"]
  }
  "alert.roles-not-kept": Record<string, never>
  "alert.run": {
    run: Arg["number"]
  }
  "alert.run-failed": {
    kind: Arg["choice"]
  }
  "alert.several": {
    subjects: Arg["list"]
    count: Arg["number"]
  }
  "alert.threshold": {
    percent: Arg["number"]
  }
  "alert.to": {
    person: Arg["mention"]
  }
  "alert.unhealthy": {
    service: Arg["text"]
  }
  "alert.unknown-reference": {
    payment: Arg["text"]
    amount: Arg["money"]
    reference: Arg["text"]
  }
  "alert.words": {
    text: Arg["text"]
  }
  "journal.action": {
    action: Arg["choice"]
  }
  "journal.actor": {
    kind: Arg["choice"]
  }
  "journal.add-plugin": {
    service: Arg["text"]
    artifact: Arg["text"]
  }
  "journal.admin-root": Record<string, never>
  "journal.announce": {
    languages: Arg["list"]
  }
  "journal.by": Record<string, never>
  "journal.cancel-run": {
    run: Arg["number"]
    kind: Arg["choice"]
  }
  "journal.clear-launch": Record<string, never>
  "journal.clear-smp-start": Record<string, never>
  "journal.complete-objective": {
    objective: Arg["text"]
  }
  "journal.concerns": Record<string, never>
  "journal.console": {
    service: Arg["text"]
    command: Arg["text"]
  }
  "journal.enforce-pack": Record<string, never>
  "journal.exempt-pack": Record<string, never>
  "journal.forget-factors": {
    keys: Arg["number"]
    sessions: Arg["number"]
  }
  "journal.grant-access": {
    days: Arg["number"]
    until: Arg["instant"]
  }
  "journal.grant-admin": Record<string, never>
  "journal.held-key": {
    label: Arg["text"]
    unlocked: Arg["choice"]
  }
  "journal.link": Record<string, never>
  "journal.minecraft": Record<string, never>
  "journal.person": {
    person: Arg["mention"]
  }
  "journal.register-key": {
    label: Arg["text"]
  }
  "journal.remove-key": {
    label: Arg["text"]
    left: Arg["number"]
  }
  "journal.rename-key": {
    label: Arg["text"]
  }
  "journal.revoke-access": {
    grants: Arg["number"]
  }
  "journal.revoke-admin": {
    below: Arg["list"]
    count: Arg["number"]
  }
  "journal.save-messages": {
    bundle: Arg["text"]
  }
  "journal.save-settings": {
    service: Arg["text"]
    group: Arg["text"]
  }
  "journal.set-alert-preference": {
    alert: Arg["choice"]
    channel: Arg["choice"]
    enabled: Arg["choice"]
  }
  "journal.set-launch": {
    to: Arg["instant"]
  }
  "journal.set-phase": {
    from: Arg["choice"]
    to: Arg["choice"]
  }
  "journal.set-phase-because": {
    from: Arg["choice"]
    to: Arg["choice"]
    reason: Arg["text"]
  }
  "journal.set-playtime": {
    seconds: Arg["duration"]
  }
  "journal.set-smp-start": {
    to: Arg["instant"]
    movedGrants: Arg["number"]
  }
  "journal.settle": {
    reference: Arg["text"]
    matched: Arg["choice"]
    days: Arg["number"]
    ordered: Arg["number"]
    received: Arg["money"]
    donation: Arg["money"]
  }
  "journal.settle-by-hand": {
    reference: Arg["text"]
    days: Arg["number"]
    ordered: Arg["number"]
    donation: Arg["money"]
  }
  "journal.start-game": Record<string, never>
  "journal.unlink": {
    selfService: Arg["choice"]
  }
  "journal.unlock-milestone": {
    milestone: Arg["text"]
  }
  "journal.web-push-subscribe": Record<string, never>
  "journal.web-push-test": {
    alert: Arg["choice"]
  }
  "journal.web-push-unsubscribe": Record<string, never>
  "journal.written": {
    detail: Arg["text"]
  }
  "note.booked": {
    reference: Arg["text"]
    payer: Arg["mention"]
    days: Arg["number"]
    until: Arg["instant"]
  }
  "note.no-season-start": Record<string, never>
  "note.payment-booked": Record<string, never>
  "note.refused-for-the-hour": {
    person: Arg["mention"]
  }
  "note.runs-from-the-grant": {
    person: Arg["mention"]
    from: Arg["instant"]
  }
  "note.too-many-codes": Record<string, never>
  "run.duration": Record<string, never>
  "run.heading": Record<string, never>
  "run.kind": {
    kind: Arg["choice"]
  }
  "run.more": {
    count: Arg["number"]
  }
  "run.no-build": Record<string, never>
  "run.notes": Record<string, never>
  "run.services": Record<string, never>
  "run.stage": {
    stage: Arg["choice"]
  }
  "run.state": {
    state: Arg["choice"]
  }
  "run.status": {
    status: Arg["choice"]
  }
  "run.successful": {
    successful: Arg["number"]
    total: Arg["number"]
  }
  "run.took": {
    took: Arg["duration"]
  }
  "steward.artifact.status": {
    status: Arg["choice"]
  }
  "steward.artifact.status-tip": {
    status: Arg["choice"]
  }
  "steward.image.drift": {
    drift: Arg["choice"]
  }
  "steward.image.drift-tip": {
    drift: Arg["choice"]
  }
  "steward.image.label": Record<string, never>
  "steward.service.docker-state": {
    state: Arg["text"]
  }
  "steward.service.health": {
    health: Arg["text"]
  }
  "steward.service.held-since": {
    since: Arg["instant"]
    state: Arg["text"]
  }
  "steward.service.no-health": Record<string, never>
  "steward.service.not-read": Record<string, never>
  "steward.service.starting": Record<string, never>
  "steward.service.state": {
    state: Arg["choice"]
  }
  "steward.service.unhealthy": Record<string, never>
}
