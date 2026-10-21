#!/usr/bin/env bash
# soak — the thermal and battery soak, on a device.
#
# The agent is expected to give way to the user when the device gets hot.
# This script runs it for thirty minutes and records what the device
# reported, so that the behaviour can be checked rather than assumed.
#
# Usage:
#   ./soak.sh 30            # thirty minutes
#   ./soak.sh 30 com.example  # against a specific package
#
# The script only reads. It never installs anything and never changes a
# setting on the device.

set -euo pipefail

MINUTES="${1:-30}"
PACKAGE="${2:-dev.lumen}"

if ! command -v adb > /dev/null 2>&1; then
    echo "adb is not on the PATH" >&2
    exit 1
fi

if [ -z "$(adb devices | sed '1d' | grep -w device)" ]; then
    echo "no device is attached" >&2
    exit 1
fi

OUTPUT="$(pwd)/soak-report-$(date +%Y%m%d-%H%M%S).csv"
echo "writing $OUTPUT"

printf 'minute,thermal_status,temperature_c,battery_percent,pss_mib,is_charging\n' > "$OUTPUT"

sample() {
    local minute="$1"
    local thermal battery pss charging temperature

    thermal="$(adb shell dumpsys thermalservice 2>/dev/null \
        | grep -m1 -E 'Temperature\{mThermalStatus=[0-9]+, mTemperature=' \
        | tr -dc '0-9.' || echo '')"

    battery="$(adb shell dumpsys battery 2>/dev/null | grep -m1 'level:' | tr -dc '0-9' || echo '')"
    charging="$(adb shell dumpsys battery 2>/dev/null | grep -m1 'AC powered:' | grep -q true && echo true || echo false)"

    pss="$(adb shell dumpsys meminfo "$PACKAGE" 2>/dev/null \
        | grep -m1 -E '^\s+TOTAL\s+PSS:' | tr -dc '0-9' || echo '')"
    if [ -n "$pss" ]; then
        pss=$((pss / 1024))
    fi

    temperature="$(adb shell dumpsys thermalservice 2>/dev/null \
        | grep -oE 'mTemperature=[0-9.]+' | head -1 | cut -d= -f2 || echo '')"

    printf '%s,%s,%s,%s,%s,%s\n' \
        "$minute" "${thermal:-unknown}" "${temperature:-}" "${battery:-}" "${pss:-}" "$charging" \
        >> "$OUTPUT"
}

echo "sampling every minute for $MINUTES minutes"
for minute in $(seq 1 "$MINUTES"); do
    sample "$minute"
    printf '\r  %s/%s minutes' "$minute" "$MINUTES"
    sleep 60
done
printf '\n'

echo
echo "=== summary ==="
awk -F, 'NR>1 {
    if ($3+0 > maxTemp) maxTemp = $3+0
    if ($4+0 != 0 && $4+0 < minBattery) minBattery = $4+0
    if ($5+0 > maxPss) maxPss = $5+0
} END {
    printf "  peak temperature:  %s C\n", (maxTemp ? maxTemp : "unknown")
    printf "  lowest battery:    %s %%\n", (minBattery ? minBattery : "unknown")
    printf "  peak agent PSS:    %s MiB (cap 2048)\n", (maxPss ? maxPss : "unknown")
}' "$OUTPUT"

if [ -f "$OUTPUT" ]; then
    echo
    echo "the report is in $OUTPUT"
    echo "paste it into the milestone, and check it against the budget in AGENTS.md §2"
fi
