#!/system/bin/sh
# makmini boot runner -- executed as root by the app after boot (and on demand).
# Mirrors Termux:Boot for scripts the user drops in the shared directories.

LOG=/dev/makmini_boot.log
D=/data/local/tmp/.gl
B1=/sdcard/Android/data/org.makmini.manager/files/boot
B2=/sdcard/makmini/boot

log() { printf '%s %s\n' "$(date +%H:%M:%S 2>/dev/null)" "$*" >> "$LOG"; }

: > "$LOG"
log "makmini_boot start (uid=$(id -u 2>/dev/null))"

# Keep the su socket available across boots.
if [ ! -S /dev/minisud.sock ]; then
    log "minisud socket missing, starting daemon"
    nohup "$D/bin/minisud" >/dev/null 2>&1 &
    sleep 1
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
