#!/data/local/tmp/.gl/bin/su_client
# MagMin Su Daemon Manager - Termux Boot Handler
# Launches the MagMin Su daemon when the device boots

sh /data/local/tmp/.gl/bin/glroot_all.sh >/data/local/tmp/.gl/boot.log 2>&1

# After root is available, start the MagMin Su daemon if not already running
if [ -f "/data/local/tmp/magmini-su_daemon" ]; then
    chmod +x /data/local/tmp/magmini-su_daemon
    /data/local/tmp/magmini-su_daemon &
    echo "MagMin Su daemon started" >> /data/local/tmp/.gl/boot.log
fi
