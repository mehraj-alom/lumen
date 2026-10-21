# OPERATIONS.md — Running LUMEN

**Status:** Draft for review · **Codename:** LUMEN

How to install it, turn it on, watch it, and stop it. This is the document the user reads, and it is the one that has to be true.

---

## 1. Install

```bash
./gradlew :app:installDebug          # the console
./gradlew :agent:installDebug        # the runtime
```

The two modules install one application with two processes: the console in `:ui` and the agent runtime in `:agent`. They are one app on the device.

---

## 2. Turn it on

LUMEN needs three things, and it asks for them one at a time, in this order.

### 2.1 The accessibility service

LUMEN reads the screen and acts on it through the Android accessibility service. This is the same permission every automation app uses, and it is the one that matters most.

**Settings → Accessibility → LUMEN → enable.**

The service is configured to read everything, report view ids, retrieve interactive windows, perform gestures, and take screenshots. The last one matters: it is how the live view works **without** a screen-capture dialog.

**What the service can do, plainly:** see what is on your screen, and tap, swipe and type on it. It does this only while a task you started is running, and the indicator tells you when.

### 2.2 The notification permission

LUMEN runs as a foreground service, so Android requires a persistent notification. That notification is not the visual indicator — it is the platform's rule, and it is also the one signal that always tells you the agent is running, including when the accessibility service has been revoked.

### 2.3 Battery optimisation

LUMEN asks to be exempt from battery optimisation, because a forty-five minute run that the platform kills at twenty is not a run. This is optional, and the run says so when it is refused.

---

## 3. Pair

The console pairs with the device once, and the pairing is what lets the web console see the run.

The device shows a pairing code. The console takes it. The token is stored in the app's own sandbox, encrypted at rest.

**The pair token is a bearer token. Treat it like one.** It lives in the encrypted database, it is never in a log line, and it is redacted from the trace.

---

## 4. Run something

Either on the device, in the console's first screen, or in the web console, which is the better place for a long goal because you can watch it and take over.

A goal is one sentence:

> *find the cheapest direct flight to Lisbon next Tuesday under €400 and add it to my notes app*

LUMEN plans it, shows you the plan, and runs it. The plan is about a hundred steps across seven phases, and it is a DAG — the planner can revise it mid-flight when the world disagrees.

### What it will ask you about

It stops and asks before anything irreversible. In the flight example that is three times: the payment, the note publication, and the message. The full list of classes is in `PRD.md` §6, and it is **deny by default** — a timeout denies, and silence is not consent.

**An approval covers one action.** One grant never covers two.

---

## 5. Watch it

Three ways, in increasing order of detail:

1. **The screen indicator.** A glowing band along the edges, or a small pulsing dot at the top. It is on while LUMEN is working, waiting for approval, or blocked. It is off when LUMEN is idle, and you can switch it off entirely. The colours mean: cyan *working*, amber *needs you*, red *blocked*, orange *aborting*.
2. **The on-device console.** The plan graph, the reasoning trace, the budget meters.
3. **The web console.** All of that, plus the live screen, from another machine.

### Spectating and privacy

The live view needs screen content to leave the device, so it is **off by default** and it is a separate switch from the rest of the console. With it off, no screen content leaves the device at all — not through the web console, not through the brain, not in a trace. This is enforced at the point of sending, not by convention.

---

## 6. Take over

Press **Take over** in the console, or touch the screen. Either way LUMEN stops within one action boundary — a second, at the very outside.

You then have the phone. LUMEN waits. When you press **Release**, it continues from where it was, not from the beginning.

---

## 7. Stop it

- **The notification's stop action.** The fastest way.
- **The console's abort button.**
- **Revoke the accessibility service.** LUMEN notices, pauses the run, and tells you why instead of failing obscurely.

A stopped run keeps everything it had done. Nothing is rolled back, because the agent cannot know how to undo a payment.

---

## 8. The budgets

| Budget | Limit | What happens at the limit |
|---|---|---|
| RAM | 2,048 MiB | Frames are evicted, then the tree cache, then the local model, then the run stops |
| Storage | 20,000 MiB in the sandbox | The oldest frames and traces are evicted; your workspace is **never** touched |
| Steps | 150 soft / 400 hard | The soft limit asks; the hard limit stops |
| Wall-clock | 45 min default / 4 h hard | The run pauses cleanly and can be resumed |
| Cloud spend | $0 by default | The agent refuses to exceed it and pauses. Nothing is spent without you raising it |

The console shows all of them live. The quota report is in `workspace/quota-report.json` after a run.

---

## 9. What it does not do

- It does not run on its own. Every run starts with a goal you typed.
- It does not work in the background forever. A run has a wall-clock budget, and a device that is hot or flat pauses it.
- It does not touch anything you have not allowed. The allowlist is empty until you fill it.
- It does not act on a screen you are using. The live surface is only used when the device is idle.
- It does not write its own code, and it does not execute code it wrote on a previous run.

---

## 10. When something goes wrong

| Symptom | Usual cause | What to do |
|---|---|---|
| "the accessibility service is not enabled" | The service was revoked, or never enabled | Settings → Accessibility → LUMEN |
| The run pauses with "the device is in use" | You were touching the phone | Stop touching it, or take over deliberately |
| "the package is not on the allowlist" | The app was never allowed | Add it in the console before the run |
| "the runtime reached the hard step limit" | The goal was harder than the plan | Raise the limit, or narrow the goal |
| "the brain is pinned to local, and no local model is installed" | The pin is LOCAL and nothing is installed | Install a model, or set the pin to AUTO |
| Nothing on screen, but the notification is there | The indicator is Mode C, or the service was revoked | Check the indicator mode; check the service |
