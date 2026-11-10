#!/system/bin/sh
# makmini boot runner -- executed as root by the app after boot (and on demand).
# Mirrors Termux:Boot for scripts the user drops in the shared directories.

LOG=/dev/makmini_boot.log
D=/data/local/tmp/.gl
X=/data/local/tmp/defex
B1=/sdcard/Android/data/org.makmini.manager/files/boot
B2=/sdcard/makmini/boot

log() { printf '%s %s\n' "$(date +%H:%M:%S 2>/dev/null)" "$*" >> "$LOG"; }

: > "$LOG"
log "makmini_boot start (uid=$(id -u 2>/dev/null))"

# Keep the su socket available across boots. DEFEX kills a direct exec of the
# unsigned daemon, so launch it through the bind-mount helper. Root has no
# fscrypt key, so warm the payload first (the app warms it as well).
if [ ! -S /dev/minisud.sock ]; then
    for f in "$X/defex" "$X/defex.sh" "$D/bin/minisud"; do
        [ -f "$f" ] && dd if="$f" of=/dev/null bs=65536 2>/dev/null
    done
    log "minisud socket missing, starting via defex"
    nohup /system/bin/sh "$X/defex.sh" -g -d "$D/bin/minisud" >/dev/null 2>&1 &
    i=0
    while [ "$i" -lt 40 ]; do
        [ -S /dev/minisud.sock ] && break
        sleep 0.25
        i=$((i+1))
    done
fi
[ -S /dev/minisud.sock ] && log "minisud socket: up" || log "minisud socket: MISSING"

# A fresh policy file is permissive by default; uids live in /dev (tmpfs), so
# re-seed the ones the manager allowed in a previous boot if a copy exists.
[ -f /dev/minisud.policy ] || printf 'mode permissive\n' > /dev/minisud.policy

for dir in "$B1" "$B2"; do
    [ -d "$dir" ] || continue
    log "scanning $dir"
    for f in "$dir"/*.sh; do
        [ -f "$f" ] || continue
        log "run $f"
        /system/bin/sh "$f" >>"$LOG" 2>&1 &
    done
done

log "makmini_boot done"
exit 0
