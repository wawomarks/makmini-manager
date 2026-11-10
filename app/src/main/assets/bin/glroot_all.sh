#!/system/bin/sh
# Idempotent full setup: arm GhostLock root, plant the init-hook, start
# a minimal permissive root daemon, and expose su helpers.

set -u
LOG=/data/local/tmp/.gl/gl_all.log
: > "$LOG"
log() { printf '%s %s\n' "$(date 2>/dev/null)" "$*" >> "$LOG"; }

log "=== glroot_all start ==="

# 0) expose minisud under an allowed safeplace so init and helpers can exec it.
sh /data/local/tmp/glroot.sh "umount -l /system/bin/fsdbg 2>/dev/null; mount --bind /data/local/tmp/.gl/bin/minisud /system/bin/fsdbg" >/dev/null 2>&1 || true

# 1) Arm root using the verified non-boot path.
#    NEVER use `glroot.sh --boot` on this model: it hangs the device after
#    `ssopt pre paint`.  Arming is done manually (adb/Termux) before this runs.
if sh /data/local/tmp/glroot.sh true >/dev/null 2>&1; then
    log "OK: root already/live"
else
    log "ERROR: root is not armed; run 'sh /data/local/tmp/glroot.sh true' manually"
    cat "$LOG"
    exit 1
fi

# 2) Wait for /odm to be populated.
GLM=/data/local/tmp/.gl/manager/glm
i=0
while [ "$i" -lt 60 ] && [ ! -x "$GLM" ]; do
    sleep 1; i=$((i+1))
done
if [ ! -x "$GLM" ]; then
    log "ERROR: glm not up at $GLM"
    exit 1
fi

# late wait for suok
i=0
while [ "$i" -lt 120 ]; do
    if grep -q 'suok=1' /odm/ghostlock/status 2>/dev/null; then
        log "OK: GhostLock ready"
        break
    fi
    sleep 1; i=$((i+1))
done

# 3) Plant the init-hook (permissive, self-restoring).
log "STEP: init-hook"
sh /data/local/tmp/defex/defex.sh -g /data/local/tmp/.gl/bin/dfinit --hold 10 >>"$LOG" 2>&1
ih_rc=$?
log "INFO: dfinit rc=$ih_rc"
if [ -e /dev/df_proof ] || [ -e /dev/df_init ]; then
    log "OK: init-hook executed (marker present)"
fi
# tidy markers for next run
sh /data/local/tmp/glroot.sh "rm -f /dev/df_init /dev/df_proof" >/dev/null 2>&1

# 4) Clear policy.bin (permissive).
sh /data/local/tmp/glroot.sh "rm -f /odm/ghostlock/policy.bin" >/dev/null 2>&1

# 5) Start permissive daemon via DEFEX detached.
sh /data/local/tmp/defex/defex.sh -g -d /data/local/tmp/.gl/bin/minisud >/dev/null 2>&1 || true
log "INFO: after minisud launch attempt"

# 6) Organize su lib exports.
cp /data/local/tmp/.gl/lib/libsu.so /data/local/tmp/.gl/lib/libsu_client_backup.so 2>/dev/null || true

# 7) Final verification results.
log "=== verification ==="
ls -l /odm/bin/minisud.sock 2>/dev/null >>"$LOG" || log "WARN: no unix socket at /odm/bin/minisud.sock"
[ -e /odm/ghostlock/policy.bin ] && log "WARN: policy file exists (not permissive)" || log "OK: permissive (policy.bin removed)"
log "COMPLETE"

echo "ALL_SETUPS_DONE" >> "$LOG"
cat "$LOG"
