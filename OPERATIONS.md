# OPERATIONS.md — Running LUMEN

**Status:** Draft for review · **Codename:** LUMEN

How to install it, turn it on, watch it, and stop it. This is the document the user reads, and it is the one that has to be true.

---

## 1. Install

```bash
./gradlew :app:installDebug          # the console
./gradlew :agent:installDebug        # the runtime
```

The two modules install one application. It runs in three processes: the default one, which hosts the console's screen; `:ui`, which hosts the accessibility service and the glass overlays it draws; and `:agent`, which hosts the runtime and is the only thing that acts on the device. They are one app on the device, and killing the screen does not stop a run.

---

## 2. Turn it on

LUMEN needs four things. The app asks for them in this order, and it shows you the console only after the first two are answered.

### 2.1 The accessibility service

LUMEN reads the screen and acts on it through the Android accessibility service. This is the same permission every automation app uses, and it is the one that matters most.

**Settings → Accessibility → LUMEN → enable.**

The first screen is the disclosure: what the service sees and does, with a button that opens the right settings page and a second that says *I have read this, and I will switch it on later*. The page is shown once — the acknowledgement is stored — and it is not a gate on the app: a user who leaves the service off reaches the console, which carries a notice naming what is missing, what it stops the agent from doing, and the one tap that changes it. The service state is re-checked on every resume, so the notice disappears by itself when the switch is thrown, and a run started without it stops at its first step rather than pretending to work.

The service is configured to read everything, report view ids, retrieve interactive windows, perform gestures, and take screenshots. The last one matters: it is how the live view works **without** a screen-capture dialog.

**What the service can do, plainly:** see what is on your screen, and tap, swipe and type on it. It does this only while a task you started is running, and the indicator tells you when.

### 2.2 The model that plans

The build carries no model of its own: no local weights ship, and the local route therefore has nothing to bind to. So the second screen asks for the three things that make a planner reachable — an endpoint that answers `POST {base}/chat/completions`, a model id, and the key that endpoint expects — plus one switch, off by default, that decides whether the agent may send screen text to it (§5).

The values are stored in this app's own private storage and are sent with each run to that endpoint and nowhere else. Nothing is bundled: there is no default provider, no demo key, no traffic until you name the model. A run started without a complete configuration fails with `brain_unavailable` before it touches the screen.

### 2.3 The notification permission

LUMEN runs as a foreground service, so Android requires a persistent notification. That notification is not the visual indicator — it is the platform's rule, and it is also the one signal that always tells you the agent is running, including when the accessibility service has been revoked. Its **Stop** action ends the live run.

Declining the prompt hides the notification; it does not stop the runtime, and the console keeps its own stop control.

### 2.4 Battery optimisation

The manifest asks for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, and **nothing in this build asks you to grant it** — no screen or dialog requests the exemption. The consequence is that a long run on a dozing phone can be killed by the platform. To avoid that: **Settings → Apps → LUMEN → Battery → Unrestricted.** `TASK.md` M15 carries the item.

---

## 3. Pair

**Not in this build.** No pairing code, token exchange or transport between the device and the web console exists in the tree yet, so the web console cannot see a run. The on-device console is the only view. `TASK.md` M15 carries the item along with the frame stream.

The console pairs with the device once, and the pairing is what lets the web console see the run.

The device shows a pairing code. The console takes it. The token is stored in the app's own sandbox, encrypted at rest.

**The pair token is a bearer token. Treat it like one.** It lives in the encrypted database, it is never in a log line, and it is redacted from the trace.

---

## 4. Run something

On the device, in the console: the goal field at the bottom of the screen, once the accessibility service is on and a model is configured (§2). The web console is not available in this build (§3), so a long goal is watched on the phone.

A goal is one sentence:

> *find the cheapest direct flight to Lisbon next Tuesday under €400 and add it to my notes app*

LUMEN plans it, tells you which step it is on and why, and runs it. The plan is about a hundred steps across seven phases, and it is a DAG — the planner can revise it mid-flight when the world disagrees.

### What it will ask you about

It stops and asks before anything irreversible. In the flight example that is three times: the payment, the note publication, and the message. The full list of classes is in `PRD.md` §6, and it is **deny by default** — a timeout denies, and silence is not consent.

**An approval covers one action.** One grant never covers two.

**Keep LUMEN in the foreground while a run can reach a gate.** The card that asks is drawn on the console screen, and the runtime has no path to raise the glass overlay card yet: background the app and a gated step simply waits, with the indicator amber, until the lease expires and the step is denied. Nothing runs without your answer either way — the waiting is the failure mode, not the safety.

---

## 5. Watch it

Three ways, in increasing order of detail:

1. **The screen indicator.** A glowing band along the edges. It is on while LUMEN is working, waiting for approval, or blocked, and it goes out when the run ends. In this build it is always the edge band: the mode setting that would switch it to the status dot or off is on a screen that is not reachable yet, so "you can switch it off entirely" is not available — revoking the accessibility service takes the window with it, and the foreground notification is what remains. The colours mean: cyan *working*, amber *needs you*, red *blocked*, orange *aborting*.
2. **The on-device console.** The step counter, the intent of the step the agent is on, the reason the planner gave for it, the reasoning trace, the budget meters, and the approvals. Each step the loop chooses is published before it acts, so a run that has gone quiet on step 31 of 80 is a run you can see thinking, not a run you have to guess about. What this build does not show is the plan graph: the state stream carries a position in the plan and a plan version, not the plan's shape, and drawing the graph is an open `TASK.md` M15 item.
3. **The web console.** All of that, plus the live screen, from another machine — not in this build; see §3.

### Spectating and privacy

The live view needs screen content to leave the device, so it is **off by default** and it is a separate switch from the rest of the console. With it off, no screen content leaves the device at all — not through the web console, not through the brain, not in a trace. This is enforced at the point of sending, not by convention.

**In this build the switch is on the setup screen, and it means one thing only: whether the planner may read the screen.** The console takes the consent when you configure the endpoint and sends it with every goal, and the runtime builds that run's egress guard from it — off, and screen text is denied at the point of sending; on, and the text the agent reads goes to the endpoint you typed. It is a per-run decision, not a process-wide setting, and the run is auditable against the consent it started with.

What is **not** wired is the rest of the spectating path: the web console cannot be turned on from the app (`SettingsScreen` is drawn but unreachable), and no frames are streamed to it, so the live view from another machine is still a `TASK.md` M15 item. Turning this switch on sends text to your model endpoint; it does not open a video path to anyone else.

---

## 6. Take over

**Touch the screen.** That is the takeover that works in this build: the idle gate sees you, and LUMEN stops injecting within one action boundary — a second, at the very outside — rather than competing with your finger. The run waits with "the device is in use".

There is no **Take over** button yet: `setTakeover` is on the IPC and the runtime honours it, but no control on the on-device console sends it, and the web console that was meant to carry it cannot pair (§3). **Stop** is available on both the console and the notification.

Once the phone is yours, LUMEN waits; resuming is the console's own control once it exists, and today a stopped run is started again rather than continued.

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
