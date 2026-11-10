#!/system/bin/sh
# Deploy the `su` shim into a GhostLock read/write layer so every app can see it.
# Assumes GhostLock is permissive (policy.bin removed) so callers bypass policy.

set -u
LOG=/dev/deploy_su.log
: > "$LOG"
glm=/data/local/tmp/.gl/manager/glm

echo "apply: phase=1" >> "$LOG"

# Layer over /system/bin so the shim appears at /system/bin/su (globally).
"$glm" rw add /system/bin >>"$LOG" 2>&1
echo "apply: rw_add_rc=$?" >> "$LOG"

# Install a stable shim over the layer's stable path.
SHIM=/data/local/tmp/.gl/bin/su_client
# Copy it to the RW upper (where the bind directs writes). Use the same
# name that many apps expect.
cp "$SHIM" /system/bin/su        2>/dev/null
chmod 4755 /system/bin/su        2>/dev/null
echo "apply: shim=$SHIM" >> "$LOG"
ls -l /system/bin/su 2>&1 >>"$LOG"

# Keep supporting files (libsu + header) under the shared .gl namespace.
cp /data/local/tmp/.gl/lib/libsu.so /system/bin/libsu.so 2>/dev/null
chmod 755 /system/bin/libsu.so 2>/dev/null

echo "apply: done" >> "$LOG"
cat "$LOG"
