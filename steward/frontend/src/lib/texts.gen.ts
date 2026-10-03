/** The values each of Steward's texts takes; written by `./gradlew :steward:generateApiTypes`. */
import type { Arg } from "@/lib/texts"

export type TextArgs = {
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
  "run.kind": {
    kind: Arg["choice"]
  }
  "run.stage": {
    stage: Arg["choice"]
  }
  "run.status": {
    status: Arg["choice"]
  }
  "run.successful": {
    successful: Arg["number"]
    total: Arg["number"]
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
