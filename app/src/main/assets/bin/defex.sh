#!/system/bin/sh
# =============================================================================
#  defex.sh -- DEFEX bypass front-end and root bootstrap (SM-G970U / IXJ1)
#
#  Run as the "shell" user (uid 2000), from adb shell / rish / Termux:
#
#     sh /data/local/tmp/defex/defex.sh <binary> [args...]
#     sh /data/local/tmp/defex/defex.sh -g <binary> [args...]   # global bind
#     sh /data/local/tmp/defex/defex.sh -d <binary> [args...]   # detach
#
#  What it does:
#    1. warms the target and helper files (the kernel root has no fscrypt key)
#    2. arms GhostLock root if it is not live yet
#    3. re-enters as root with a newline-separated argument file (no quoting)
#    4. bind-mounts the /data payload over a DEFEX "safeplace" and execs it
#       with the correct argv[0], inside a private mount namespace by default
#
#  Safeplaces: /system/bin/{fsdbg,smdexe,ddexe,resetreason,atrace}
#
#  Root is transient (per boot).  Warming is required: if the payload's pages
#  are not in the page cache, exec fails with ENOKEY ("Required key not
#  available").  Re-run after every reboot once root has been re-armed.
# =============================================================================

set -u

D=/data/local/tmp/defex
GL=/data/local/tmp/glroot.sh
BIN=$D/defex
SELF=$D/defex.sh

SAFES="/system/bin/fsdbg /system/bin/smdexe /system/bin/ddexe /system/bin/resetreason /system/bin/atrace"

say() { printf '%s\n' "$*"; }
die() { printf 'defex: %s\n' "$*" >&2; exit 1; }

warm() {
  [ -f "$1" ] || return 0
  dd if="$1" of=/dev/null bs=65536 >/dev/null 2>&1 || true
}

# Only toybox applets and the five safeplaces may be exec'd from the kernel
# root context; a standalone binary such as /system/bin/awk is killed by
# DEFEX.  Keep the root-side helpers to shell builtins + toybox.
is_mounted() {
  grep -qF " $1 " /proc/self/mountinfo 2>/dev/null
}

free_safe() {
  for s in $SAFES; do
    [ -f "$s" ] || continue
    is_mounted "$s" && continue
    printf '%s\n' "$s"
    return 0
  done
  return 1
}

clean_stale() {
  for s in $SAFES; do
    if is_mounted "$s"; then
      umount -l "$s" 2>/dev/null && say "defex: unmounted stale $s"
    fi
  done
}

# ---------------------------------------------------------------------------
#  Root side
# ---------------------------------------------------------------------------
if [ "$(id -u)" = "0" ]; then
  if [ "${1:-}" = "--clean" ]; then
    clean_stale
    exit 0
  fi

  if [ "${1:-}" = "--root" ]; then
    shift
    REQ="${1:-}"
    [ -n "$REQ" ] && [ -f "$REQ" ] || die "missing request file"
    # Line 1 is argc; each following line is one argument (verbatim).
    set --
    first=1
    while IFS= read -r line || [ -n "$line" ]; do
      if [ "$first" = "1" ]; then
        first=0
        continue
      fi
      set -- "$@" "$line"
    done < "$REQ"
    rm -f "$REQ"
    [ "$#" -gt 0 ] || die "empty request"
  fi

  [ "$#" -gt 0 ] || die "usage: defex.sh [-g] [-d] <binary> [args...]"
  [ -x "$BIN" ] || die "$BIN missing or not executable"

  SAFE="$(free_safe)" || die "no free safeplace"
  trap 'umount -l "$SAFE" 2>/dev/null' EXIT

  mount --bind "$BIN" "$SAFE" || die "bootstrap bind $BIN -> $SAFE failed"
  export DEFEX_OUTER="$SAFE"
  exec "$SAFE" "$@"
fi

# ---------------------------------------------------------------------------
#  Shell side (uid 2000)
# ---------------------------------------------------------------------------
[ "$#" -gt 0 ] || die "usage: defex.sh [-g] [-d] <binary> [args...]"
[ -x "$GL" ] || die "$GL missing"
[ -x "$BIN" ] || die "$BIN missing (push the defex binary)"

REQ="$D/req.$$"
{
  printf '%s\n' "$#"
  for a in "$@"; do printf '%s\n' "$a"; done
} > "$REQ" || die "cannot write $REQ"
chmod 600 "$REQ" 2>/dev/null

# Root cannot decrypt /data: warm every file the root stage will read.
warm "$REQ"
warm "$SELF"
warm "$BIN"
case "${1:-}" in
  -g|-d) tgt="${2:-}" ;;
  *)     tgt="${1:-}" ;;
esac
case "$tgt" in /*) warm "$tgt" ;; esac

# Arm root if it is not live yet (glroot's arm is a no-op when it is).
if ! sh "$GL" true >/dev/null 2>&1; then
  rm -f "$REQ"
  die "root is not available (try: sh $GL --status)"
fi

exec sh "$GL" "sh $SELF --root $REQ"
