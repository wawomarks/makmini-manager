#!/system/bin/sh
# makmini rx -- run an unsigned /data or /odm binary as root.
#
# DEFEX kills a direct exec of an unsigned payload from a root context.
# Bind-mounting the payload over one of the five "safeplace" paths makes the
# kernel accept it.  This script does that, runs the binary, and unmounts.
#
#   sh /data/local/tmp/.gl/bin/rx <binary> [args...]
#
# Root has no fscrypt key, so the binary must already be in the page cache.
# The manager app warms it before calling; from adb use:
#   adb shell cat <binary> > /dev/null
#
# Prefer `su -x <binary> [args...]`: it does the same thing without needing
# to read this script.

BIN=${1:-}
[ -n "$BIN" ] || { echo "usage: rx <binary> [args...]" >&2; exit 2; }
shift
[ -f "$BIN" ] || { echo "rx: not found: $BIN" >&2; exit 127; }
[ "$(id -u)" = 0 ] || { echo "rx: must run as root (su -c 'sh rx <binary>')" >&2; exit 1; }

SAFES="/system/bin/fsdbg /system/bin/smdexe /system/bin/ddexe /system/bin/resetreason /system/bin/atrace"

# Prefer the daemon: it preserves argv[0], which multicall binaries
# (busybox, toybox) need. Binding over a safeplace renames argv[0] to the
# safeplace path, so they would look for an applet by that name.
#
# The client itself is unsigned too, so it cannot be exec'd directly from a
# root shell either; wrap it in a second safeplace.
CLIENT="${0%/*}/su_client"
[ -x "$CLIENT" ] || CLIENT=/odm/bin/su
[ -x "$CLIENT" ] || CLIENT=/data/local/tmp/.gl/bin/su_client
if [ -S /dev/minisud.sock ] && [ -x "$CLIENT" ]; then
    for s in $SAFES; do
        [ -f "$s" ] || continue
        grep -qF " $s " /proc/self/mountinfo 2>/dev/null && continue
        mount --bind "$CLIENT" "$s" 2>/dev/null || continue
        "$s" -x "$BIN" "$@"
        rc=$?
        umount -l "$s" 2>/dev/null
        exit $rc
    done
    echo "rx: daemon up but no free DEFEX safeplace for the client" >&2
    exit 4
fi

for s in $SAFES; do
    [ -f "$s" ] || continue
    grep -qF " $s " /proc/self/mountinfo 2>/dev/null && continue
    mount --bind "$BIN" "$s" 2>/dev/null || continue
    "$s" "$@"
    rc=$?
    umount -l "$s" 2>/dev/null
    exit $rc
done

echo "rx: no free DEFEX safeplace" >&2
exit 4
