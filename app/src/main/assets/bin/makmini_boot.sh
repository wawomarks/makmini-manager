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

# Keep the su socket available across boots. DEFEX kills a direct exec of the
# unsigned daemon, so bind it over a safeplace and exec that path. The bind is
# left in place: /system/bin/fsdbg then doubles as the root exec shim
# (`fsdbg --exec <binary>`). This script already runs as root, so it must NOT
# call defex.sh: that would re-enter root through the mpnl channel and
# deadlock it.
SAFE=/system/bin/fsdbg
if [ ! -S /dev/minisud.sock ]; then
    [ -f "$D/bin/minisud" ] && dd if="$D/bin/minisud" of=/dev/null bs=65536 2>/dev/null
    log "minisud socket missing, starting via safeplace bind"
    grep -qF " $SAFE " /proc/self/mountinfo 2>/dev/null && umount -l "$SAFE" 2>/dev/null
    if [ -x "$D/bin/minisud" ] && mount --bind "$D/bin/minisud" "$SAFE"; then
        nohup "$SAFE" </dev/null >/dev/null 2>&1 &
        i=0
        while [ "$i" -lt 40 ]; do
            [ -S /dev/minisud.sock ] && break
            sleep 0.25
            i=$((i+1))
        done
    else
        log "safeplace bind failed"
    fi
fi
[ -S /dev/minisud.sock ] && log "minisud socket: up" || log "minisud socket: MISSING"

# Reinstall the su shim and the rx helper into /odm (tmpfs resets each boot).
if [ -f "$D/bin/su_client" ]; then
    grep -q ' /odm ' /proc/mounts 2>/dev/null || \
        mount -t tmpfs -o rw,exec,nosuid,nodev,mode=755,size=32m tmpfs /odm 2>/dev/null
    mkdir -p /odm/bin /odm/ghostlock 2>/dev/null
    cp -f "$D/bin/su_client" /odm/bin/su 2>/dev/null && chmod 755 /odm/bin/su
    [ -f "$D/bin/rx" ] && cp -f "$D/bin/rx" /odm/bin/rx 2>/dev/null && chmod 755 /odm/bin/rx
    # /odm/bin is on PATH for the shell and for app processes, so `su` resolves
    # to this shim without needing /system/bin/su (absent on this firmware).
    log "su shim: $(ls -l /odm/bin/su 2>/dev/null | tr -s ' ')"
fi
log "exec shim: $SAFE --exec <binary>  (or sh $D/bin/rx <binary>)"

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
