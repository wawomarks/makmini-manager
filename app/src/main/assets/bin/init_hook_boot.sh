#!/system/bin/sh
# Post-glroot init-hook boot plug-in.
# Runs after GhostLock has armed root (`suok=1`) and installed its tools.
# Prints progress to /dev/init_hook_boot.log (tmpfs, safe even without /data key).

LOG=/dev/init_hook_boot.log
: > "$LOG"
# expose daemon safely first
sh /data/local/tmp/glroot.sh "umount -l /system/bin/fsdbg 2>/dev/null; mount --bind /data/local/tmp/.gl/bin/minisud /system/bin/fsdbg" >/dev/null 2>&1 || true
write() { printf '%s\n' "$*" >> "$LOG"; }

write "[init-hook-boot] start $(date 2>/dev/null)"

# Wait (up to 3 min) for GhostLock to finish.
i=0
while [ "$i" -lt 180 ]; do
    if grep -q '^status:.*suok=1' /odm/ghostlock/status 2>/dev/null; then
        write "[init-hook-boot] GhostLock is ready"
        break
    fi
    sleep 1
    i=$((i+1))
done
if [ "$i" -eq 180 ]; then
    write "[init-hook-boot] GhostLock not ready; aborting"
    exit 1
fi

# Optional: get the DEFEX loader warmed.
TARGET=/data/local/tmp/.gl/bin/dfinit
write "[init-hook-boot] waiting for root, then running init-hook: $TARGET"
if [ ! -x "$TARGET" ]; then
    write "[init-hook-boot] $TARGET missing; copying from legacy path"
    cp /data/local/tmp/dfinit/dfinit "$TARGET" 2>/dev/null
    chmod 755 "$TARGET" 2>/dev/null
fi

# Use defex in global mode so the patch is system-wide.
sh /data/local/tmp/defex/defex.sh -g "$TARGET" --hold 20 >>"$LOG" 2>&1
rc=$?
write "[init-hook-boot] dfinit rc=$rc last marker:"
[ -e /dev/df_proof ] && write "$(< /dev/df_proof 2>/dev/null)"
exit "$rc"
