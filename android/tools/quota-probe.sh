#!/usr/bin/env bash
# quota-probe — report the live RAM and storage footprint of the agent runtime
# against the budgets in AGENTS.md §2.
#
# Usage:
#   ./quota-probe.sh                 # the default application id
#   ./quota-probe.sh dev.lumen.agent # a specific process
#
# The script needs a connected device and `adb` on the PATH. It reads only; it
# never writes to the device.

set -euo pipefail

PACKAGE="${1:-dev.lumen.agent}"
RAM_LIMIT_MIB=2048
STATE_LIMIT_MIB=2000

if ! command -v adb > /dev/null 2>&1; then
    echo "adb is not on the PATH" >&2
    exit 1
fi

if [ -z "$(adb devices | sed '1d' | grep -w device)" ]; then
    echo "no device is attached" >&2
    exit 1
fi

echo "=== agent runtime footprint: $PACKAGE ==="
echo

# --- RAM ---------------------------------------------------------------------
# dumpsys meminfo reports the PSS of a process, which is what the operating
# system would charge it for.
PSS_KB="$(adb shell dumpsys meminfo "$PACKAGE" 2>/dev/null | grep -m1 -E '^\s+TOTAL\s+PSS:' | tr -dc '0-9' || true)"

if [ -z "${PSS_KB:-}" ]; then
    PSS_KB="$(adb shell dumpsys meminfo "$PACKAGE" 2>/dev/null | grep -m1 -iE 'TOTAL PSS' | tr -dc '0-9' || true)"
fi

if [ -n "${PSS_KB:-}" ]; then
    PSS_MIB=$((PSS_KB / 1024))
    echo "RAM (PSS)      ${PSS_MIB} MiB / ${RAM_LIMIT_MIB} MiB"
    if [ "$PSS_MIB" -ge "$RAM_LIMIT_MIB" ]; then
        echo "  HARD STOP: the runtime is at or past the cap"
    elif [ "$PSS_MIB" -ge $((RAM_LIMIT_MIB * 95 / 100)) ]; then
        echo "  CRITICAL: the local model should be unloaded and inference moved to the cloud"
    elif [ "$PSS_MIB" -ge $((RAM_LIMIT_MIB * 85 / 100)) ]; then
        echo "  ELEVATED: frames and then the tree cache should be evicted"
    fi
else
    echo "RAM (PSS)      unavailable: is the package running?"
fi
echo

# --- storage -----------------------------------------------------------------
# The sandbox is app-scoped, so it lives under the app's data directory.
echo "storage (sandbox buckets, KiB):"
for bucket in models frames traces state workspace; do
    KIB="$(adb shell "du -sk /data/data/$PACKAGE/files/sandbox/$bucket 2>/dev/null" | tr -dc '0-9' || true)"
    if [ -n "${KIB:-}" ]; then
        printf '  %-10s %8s KiB\n' "$bucket" "$KIB"
    else
        printf '  %-10s %8s\n' "$bucket" "-"
    fi
done

TOTAL_KIB="$(adb shell "du -sk /data/data/$PACKAGE/files/sandbox 2>/dev/null" | tr -dc '0-9' || true)"
if [ -n "${TOTAL_KIB:-}" ]; then
    TOTAL_MIB=$((TOTAL_KIB / 1024))
    echo
    echo "storage total   ${TOTAL_MIB} MiB / ${STATE_LIMIT_MIB} MiB"
    if [ "$TOTAL_MIB" -ge "$STATE_LIMIT_MIB" ]; then
        echo "  HARD STOP: the sandbox is at or past the cap"
    fi
fi
