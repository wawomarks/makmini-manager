#!/data/local/tmp/.gl/bin/su_client
# MagMin Su Daemon Manager - Enhanced Termux Boot Handler
# Launches the MagMin Su daemon when the device boots with better lifecycle management

sh /data/local/tmp/.gl/bin/glroot_all.sh >/data/local/tmp/.gl/boot.log 2>&1

# Wait for root to be fully established
for i in {1..10}; do
    if su -c "echo ready" 2>/dev/null; then
        echo "Root is ready for MagMin Su daemon initialization" >> /data/local/tmp/.gl/boot.log
        break
    fi
    sleep 1
done

# After root is available, start the MagMin Su daemon if not already running
if [ -f "/data/local/tmp/magmini-su/daemon.sh" ]; then
    echo "Starting MagMin Su daemon..." >> /data/local/tmp/.gl/boot.log
    chmod +x /data/local/tmp/magmini-su/daemon.sh
    nohup /data/local/tmp/magmini-su/daemon.sh >/data/local/tmp/magmini-su/daemon.log 2>&1 &
    echo "MagMin Su daemon started in background" >> /data/local/tmp/.gl/boot.log
    # Store daemon PID for management
    echo $! > /data/local/tmp/magmini-su/daemon.pid
fi
