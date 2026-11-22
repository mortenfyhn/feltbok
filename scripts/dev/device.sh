#!/usr/bin/env bash
# Shares Android devices between worktrees, so parallel agents don't install over each other.
# Each worktree leases its own emulator, at most $max at once (each needs 2-3 GB of RAM). The phone
# needs no lease: device recipes use it only when passed --phone, which sets FELTBOK_PHONE.
#
#   device.sh emu       lease an emulator for this worktree and boot it; prints its serial
#   device.sh emu-stop  shut down this worktree's emulator and drop its lease
#   device.sh serial    print the serial that device recipes target
#   device.sh adb ARGS  run adb on that serial
#
# A lease is a file named after the serial, holding the worktree path. It's stale once that
# emulator's process is gone, or its worktree is.
set -euo pipefail

max=3
avd=Medium_Phone_API_36.1
dir="${XDG_RUNTIME_DIR:-/tmp}/feltbok-devices"
root=$(cd "$(dirname "$0")/../.." && pwd)
mkdir -p "$dir"

die() { echo "$*" >&2; exit 1; }

# The launcher hands over to a separate qemu process, so look that up by port rather than keep a
# PID. A lease gets 30 s to start it.
running() {
    pgrep -f -- "qemu-system.* -port ${1#emulator-}( |$)" > /dev/null \
        || [ $(($(date +%s) - $(stat -c %Y "$dir/$1"))) -lt 30 ]
}

reclaim() {
    local f s
    for f in "$dir"/emulator-*; do
        [ -e "$f" ] || continue
        s=$(basename "$f")
        if ! running "$s"; then
            rm -f "$f"
        elif [ ! -d "$(cat "$f")" ]; then
            adb -s "$s" emu kill > /dev/null 2>&1 || true
            rm -f "$f"
        fi
    done
}

own_lease() {
    local f
    for f in "$dir"/emulator-*; do
        [ -e "$f" ] && [ "$(cat "$f")" = "$root" ] && { basename "$f"; return; }
    done
    return 1
}

phone_serial() {
    adb devices | awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ { print $1; exit }'
}

serial() {
    local s
    if [ -n "${FELTBOK_PHONE:-}" ]; then
        s=$(phone_serial)
        [ -n "$s" ] || die "No phone attached."
    else
        s=$(own_lease) || die "This worktree has no emulator. Run \`just emu\` first, or pass --phone to use the phone (ask the maintainer before you do)."
    fi
    echo "$s"
}

emu() {
    local s port leases
    exec 9> "$dir/.lock"
    flock 9
    reclaim
    if s=$(own_lease); then
        flock -u 9
    else
        leases=("$dir"/emulator-*)
        [ -e "${leases[0]}" ] || leases=()
        if [ "${#leases[@]}" -ge "$max" ]; then
            echo "All $max emulators are in use, by these worktrees:" >&2
            cat "${leases[@]}" | sed 's/^/  /' >&2
            die "Wait for one to be released, then run \`just emu\` again. Don't stop emulators you didn't start."
        fi
        for port in $(seq 5554 2 5584); do
            [ -e "$dir/emulator-$port" ] || adb devices | grep -q "^emulator-$port\b" || break
        done
        s=emulator-$port
        # -read-only lets several instances of one AVD run at once; it boots from the Quick Boot
        # snapshot in seconds and discards changes on exit. Host GPU: the software renderer crashes
        # under Fedora's SELinux (execheap), and freshly created AVDs crash SurfaceFlinger when
        # headless. The display is set below to 360 x 760 dp, the size of the docs/mockups/ phones.
        setsid nohup "$ANDROID_HOME/emulator/emulator" -avd "$avd" -read-only -port "$port" \
            -no-audio -no-boot-anim -gpu host > /dev/null 2>&1 &
        echo "$root" > "$dir/$s"
        flock -u 9
    fi
    until [ "$(adb -s "$s" shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" = 1 ]; do
        running "$s" || { rm -f "$dir/$s"; die "The emulator exited while booting. Is an emulator running that \`just emu\` didn't start? All of them must use -read-only."; }
        sleep 1
    done
    adb -s "$s" shell 'wm size 1080x2280 && wm density 480'
    echo "$s"
}

emu_stop() {
    local s
    s=$(own_lease) || return 0
    adb -s "$s" emu kill > /dev/null 2>&1 || true
    rm -f "$dir/$s"
}

case "${1:-}" in
    emu) emu ;;
    emu-stop) emu_stop ;;
    serial) serial ;;
    adb) shift; s=$(serial); exec adb -s "$s" "$@" ;;
    *) die "usage: $0 emu | emu-stop | serial | adb ARGS" ;;
esac
