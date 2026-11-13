---
status: proposed
---

# Routines are runtime-owned controls

People need to manage scheduled work from an agent's page, with the schedule and advanced settings shown in Hermes's mobile app. The runtime already keeps and executes that work, including changes made by its own tools while the phone is away.

Decision (proposed): show **Routines** for an agent advertising that optional capability, using the bridge's generic typed contract ([enclos ADR 0050](https://github.com/victorbonnet/enclos/blob/main/docs/adr/0050-routines-stay-with-the-runtime.md)). The native editor translates Daily, Weekly and Monthly into cron schedules, Repeat into a minute interval, Once into an absolute timestamp, and Custom into a cron expression. It shows the runtime's scheduling timezone and observed next/last-run times; the phone neither schedules executions nor predicts them.

Controls require a connected host and confirmed runtime replies. Nothing is queued offline or automatically retried after an uncertain outcome. Saving uses the revision of the definition that was opened; a conflict requires reloading rather than replacing a newer edit. Creating, editing, enabling, running and deleting use the same request correlation. Native fields outside the editor survive updates; routines with external delivery targets are shown read-only. App delivery choices come from the bridge, which authorizes them independently of the phone.

Reason: runtime ownership avoids a second scheduler and gives another runtime, including a future OpenClaw plugin, the same app flow. The capability and supported-field catalog expose actual runtime behaviour without assuming every runtime has Hermes's advanced settings. Android implements the controls first; iOS follows the same contract when built.
