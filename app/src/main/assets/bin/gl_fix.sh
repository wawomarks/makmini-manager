#!/system/bin/sh
# =============================================================================
#  gl_fix.sh -- GhostLock root helper (runs as ROOT via modprobe_path)
#
#  Idempotent. Installs the policy-enforcing su client, BusyBox applets, the
#  read/write bind layer, syncs the manager policy, and ensures or restarts the
#  sud daemon. Safe to run at any time.
#
#  IMPORTANT: this process runs without the fscrypt key, so it CANNOT create or
#  write inodes under /data. It only reads /data (page cache, warmed by uid
#  2000) and writes to /odm (tmpfs) or the rw tmpfs. All persistent /data
#  writes are done by glm as uid 2000.
#
#  Request file: /data/local/tmp/.gl/manager/req
#     cmd=sync|restart|rw      (default sync)
#     op=add|rm|list|restore   (cmd=rw)
#     path=/system/etc         (cmd=rw add/rm)
#     token=<opaque>           echoed back into the status file
# =============================================================================
D=/data/local/tmp/.gl
M=$D/manager
LOG=/dev/gl_fix.log       # tmpfs: writable by root without an fscrypt key
ST=/odm/ghostlock/status
RWL=$D/rw.list          # persistent path list (uid 2000)
RWD=$D/rw               # live tmpfs upper layer
RWS=$D/rwstore          # persistent mirror (uid 2000)
RWO=/odm/ghostlock/rw.last
ZMAX=64

# path -> safe name ("/system/etc" -> "system_etc")
rw_id() { printf '%s' "$1" | sed 's#^/*##; s#[/.]#_#g'; }

is_mnt() { grep -q " $1 " /proc/mounts 2>/dev/null; }

rw_ensure_tmpfs() {
  is_mnt "$RWD" && return 0
  mount -t tmpfs -o rw,exec,nosuid,nodev,mode=755,size=512m tmpfs "$RWD" 2>/dev/null
}

# $1 = target path. Rebuilds the live copy from the persistent mirror (or from
# the original path when the mirror is empty), then bind-mounts it.
rw_mount_one() {
  p=${1%/}
  [ -n "$p" ] || return 0
  [ -e "$p" ] || { echo "rw: $p is missing (skipped)"; return 0; }
  is_mnt "$p" && return 0
  rw_ensure_tmpfs
  lv=$RWD/$(rw_id "$p")
  st=$RWS/$(rw_id "$p")
  if [ -d "$p" ]; then
    mkdir -p "$lv" 2>/dev/null
    if [ -z "$(ls -A "$lv" 2>/dev/null)" ]; then
      if [ -d "$st" ] && [ -n "$(ls -A "$st" 2>/dev/null)" ]; then
        cp -a "$st/." "$lv/" 2>/dev/null
      else
        cp -a "$p/." "$lv/" 2>/dev/null
      fi
    fi
    mount --bind "$lv" "$p" 2>/dev/null && echo "rw: $p is read-write" \
      || echo "rw: bind failed for $p"
  elif [ -f "$p" ]; then
    mkdir -p "$RWD" 2>/dev/null
    if [ -f "$st" ]; then
      cp -f "$st" "$lv" 2>/dev/null
    elif [ ! -f "$lv" ]; then
      cp -p "$p" "$lv" 2>/dev/null
    fi
    mount --bind "$lv" "$p" 2>/dev/null && echo "rw: $p is read-write" \
      || echo "rw: bind failed for $p"
  else
    echo "rw: $p is not a file or directory"
  fi
}

rw_apply_all() {
  [ -f "$RWL" ] || return 0
  while IFS= read -r p; do
    [ -n "$p" ] || continue
    rw_mount_one "$p"
  done < "$RWL"
}

rw_show() {
  echo "rw layer:"
  if [ -f "$RWL" ] && [ -s "$RWL" ]; then
    while IFS= read -r p; do
      [ -n "$p" ] || continue
      if is_mnt "$p"; then s=mounted; else s=not-mounted; fi
      echo "  $p [$s]"
    done < "$RWL"
  else
    echo "  (empty)"
  fi
  echo "  backing: $RWD"
  df -h "$RWD" 2>/dev/null | tail -1 | sed 's/^/  /'
}

{
  echo "=== helper $(date) uid=$(id -u) ctx=$(cat /proc/self/attr/current 2>/dev/null) ==="

  # ---- request --------------------------------------------------------------
  TOKEN=""; CMD=sync; OP=""; RWPATH=""
  if [ -f "$M/req" ]; then
    while IFS='=' read -r k v; do
      case "$k" in
        token) TOKEN="$v" ;;
        cmd)   CMD="$v" ;;
        op)    OP="$v" ;;
        path)  RWPATH="$v" ;;
      esac
    done < "$M/req"
  fi
  echo "req cmd=$CMD op=$OP path=$RWPATH token=$TOKEN"

  # ---- /odm -----------------------------------------------------------------
  grep -q ' /odm ' /proc/mounts 2>/dev/null || \
    mount -t tmpfs -o rw,exec,nosuid,nodev,mode=755,size=32m tmpfs /odm
  mkdir -p /odm/bin /odm/ghostlock
  chmod 755 /odm /odm/bin /odm/ghostlock 2>/dev/null

  # ---- managed su client ----------------------------------------------------
  if [ ! -s /odm/bin/su ] || ! cmp -s "$D/sud_policy" /odm/bin/su 2>/dev/null; then
    if cp "$D/sud_policy" /odm/bin/.su.new 2>/dev/null; then
      chmod 755 /odm/bin/.su.new 2>/dev/null
      mv /odm/bin/.su.new /odm/bin/su 2>/dev/null && echo "installed patched su"
    else
      echo "WARN: cannot read $D/sud_policy (cold inode? warm it from uid 2000)"
    fi
  fi
  [ -f /odm/bin/runroot.sh ] || cp "$D/runroot.sh" /odm/bin/runroot.sh 2>/dev/null
  [ -f /odm/bin/keylink ]    || cp "$D/keylink"    /odm/bin/keylink 2>/dev/null
  if [ ! -s /odm/bin/glm ] || ! cmp -s "$M/glm" /odm/bin/glm 2>/dev/null; then
    cp "$M/glm" /odm/bin/.glm.new 2>/dev/null && {
      chmod 755 /odm/bin/.glm.new 2>/dev/null
      mv /odm/bin/.glm.new /odm/bin/glm 2>/dev/null
    }
  fi
  chmod 755 /odm/bin/runroot.sh /odm/bin/keylink /odm/bin/glm /odm/bin/su 2>/dev/null
  rm -f /odm/bin/sud /odm/bin/sud_gl /odm/bin/sud.new /odm/bin/su.new \
        /odm/bin/su.orig /odm/bin/.su.new 2>/dev/null

  # ---- busybox --------------------------------------------------------------
  # /odm/bin/busybox plus one symlink per applet. /system/bin precedes /odm/bin
  # in PATH, so toybox keeps winning shared names; BusyBox fills the gaps only.
  if [ -s "$D/busybox" ]; then
    if [ ! -s /odm/bin/busybox ] || ! cmp -s "$D/busybox" /odm/bin/busybox 2>/dev/null; then
      if cp "$D/busybox" /odm/bin/.bb.new 2>/dev/null; then
        chmod 755 /odm/bin/.bb.new 2>/dev/null
        mv /odm/bin/.bb.new /odm/bin/busybox 2>/dev/null && echo "installed busybox"
      else
        echo "WARN: cannot read $D/busybox (cold inode?)"
      fi
    fi
    if [ -s /odm/bin/busybox ]; then
      NBB=0
      set -f
      for a in [ [[ acpid add-shell addgroup adduser adjtimex arch arp arping ash awk base64 basename bc beep blkdiscard blkid blockdev bootchartd brctl bunzip2 bzcat bzip2 cal cat chat chattr chgrp chmod chown chpasswd chpst chroot chrt chvt cksum clear cmp comm conspy cp cpio crond crontab cryptpw cttyhack cut date dc dd deallocvt delgroup deluser depmod devmem df dhcprelay diff dirname dmesg dnsd dnsdomainname dos2unix dpkg dpkg-deb du dumpkmap dumpleases echo ed egrep eject env envdir envuidgid ether-wake expand expr factor fakeidentd fallocate false fatattr fbset fbsplash fdflush fdformat fdisk fgconsole fgrep find findfs flock fold free freeramdisk fsck fsck.minix fsfreeze fstrim fsync ftpd ftpget ftpput fuser getopt getty grep groups gunzip gzip halt hd hdparm head hexdump hexedit hostid hostname httpd hush hwclock i2cdetect i2cdump i2cget i2cset i2ctransfer id ifconfig ifdown ifenslave ifplugd ifup inetd init insmod install ionice iostat ip ipaddr ipcalc ipcrm ipcs iplink ipneigh iproute iprule iptunnel kbd_mode kill killall killall5 klogd last less link linux32 linux64 linuxrc ln loadfont loadkmap logger login logname logread losetup lpd lpq lpr ls lsattr lsmod lsof lspci lsscsi lsusb lzcat lzma lzop makedevs makemime man md5sum mdev mesg microcom mkdir mkdosfs mke2fs mkfifo mkfs.ext2 mkfs.minix mkfs.vfat mknod mkpasswd mkswap mktemp modinfo modprobe more mount mountpoint mpstat mt mv nameif nanddump nandwrite nbd-client nc netstat nice nl nmeter nohup nologin nproc nsenter nslookup ntpd nuke od openvt partprobe passwd paste patch pgrep pidof ping ping6 pipe_progress pivot_root pkill pmap popmaildir poweroff powertop printenv printf ps pscan pstree pwd pwdx raidautorun rdate rdev readahead readlink readprofile realpath reboot reformime remove-shell renice reset resize resume rev rm rmdir rmmod route rpm rpm2cpio rtcwake run-init run-parts runlevel runsv runsvdir rx script scriptreplay sed sendmail seq setarch setconsole setfattr setfont setkeycodes setlogcons setpriv setserial setsid setuidgid sh sha1sum sha256sum sha3sum sha512sum showkey shred shuf slattach sleep smemcap softlimit sort split ssl_client start-stop-daemon stat strings stty su sulogin sum sv svc svlogd svok swapoff swapon switch_root sync sysctl syslogd tac tail tar taskset tc tcpsvd tee telnet telnetd test tftp tftpd time timeout top touch tr traceroute traceroute6 true truncate ts tty ttysize tunctl ubiattach ubidetach ubimkvol ubirename ubirmvol ubirsvol ubiupdatevol udhcpc udhcpc6 udhcpd udpsvd uevent umount uname unexpand uniq unix2dos unlink unlzma unshare unxz unzip uptime users usleep uudecode uuencode vconfig vi vlock volname w wall watch watchdog wc wget which who whoami whois xargs xxd xz xzcat yes zcat zcip; do
        case "$a" in busybox|su|sh|glm|keylink|runroot.sh|bb) continue ;; esac
        [ -e "/odm/bin/$a" ] && continue
        ln -s busybox "/odm/bin/$a" 2>/dev/null && NBB=$((NBB + 1))
      done
      set +f
      echo "busybox applets linked=$NBB"
    fi
  else
    echo "busybox: not present at $D/busybox"
  fi

  # ---- policy ---------------------------------------------------------------
  # Fail closed: if a policy exists but its bytes cannot be read (cold fscrypt
  # inode), leave an EMPTY policy file behind, which allows root/shell only.
  MODE=permissive
  if [ -f "$M/policy.bin" ]; then
    : > /odm/ghostlock/policy.bin 2>/dev/null
    chmod 644 /odm/ghostlock/policy.bin 2>/dev/null
    cp "$M/policy.bin" /odm/ghostlock/policy.bin 2>/dev/null || \
      echo "WARN: cold policy.bin; enforcing empty policy"
    MODE=enforce
  else
    rm -f /odm/ghostlock/policy.bin 2>/dev/null
  fi
  [ -f /odm/ghostlock/su.log ] || : > /odm/ghostlock/su.log
  chmod 666 /odm/ghostlock/su.log 2>/dev/null

  # ---- read/write layer -----------------------------------------------------
  # No overlayfs in this kernel. Each path gets a copy in a tmpfs (live, fully
  # writable by root) mirrored to /data by glm. At boot/apply, the mirror is
  # copied back into the tmpfs and bind-mounted over the original path.
  : > "$RWO" 2>/dev/null
  rw_ensure_tmpfs
  if [ "$CMD" = "rw" ]; then
    {
      case "$OP" in
        add) rw_mount_one "$RWPATH" ;;
        rm)  p=${RWPATH%/}
             umount -l "$p" 2>/dev/null
             rm -rf "$RWD/$(rw_id "$p")" 2>/dev/null
             echo "rw: $p detached" ;;
        list) rw_show ;;
        reset|clean)
             # detach every target first, otherwise the binds keep the old
             # tmpfs alive and the reset is a no-op
             if [ -f "$RWL" ]; then
               while IFS= read -r rp; do
                 [ -n "$rp" ] && umount -l "${rp%/}" 2>/dev/null
               done < "$RWL"
             fi
             umount -l "$RWD" 2>/dev/null
             rw_ensure_tmpfs
             echo "rw: live layer reset" ;;
        *)    rw_apply_all ;;
      esac
    } >> "$RWO" 2>&1
    [ "$OP" = "rm" ] || rw_apply_all >> "$RWO" 2>&1
  else
    rw_apply_all >> "$RWO" 2>&1
  fi
  chmod 644 "$RWO" 2>/dev/null

  # ---- enforcement self-test (cmd=test) ------------------------------------
  if [ "$CMD" = "test" ]; then
    TO=/odm/ghostlock/test.out
    : > "$TO" 2>/dev/null
    for u in 2000 10291 10123 1000 2001; do
      sh /odm/bin/runroot.sh "$D/asuid" "$u" /odm/bin/su -c 'test "$(id -u)" = 0' >/dev/null 2>&1
      rc=$?
      [ "$rc" = 0 ] && r=allow || r=deny
      echo "  uid $u $r (rc=$rc)" >> "$TO"
    done
    chmod 644 "$TO" 2>/dev/null
  fi

  # ---- harden the persistent store -----------------------------------------
  chmod 755 "$D" "$M" 2>/dev/null
  chmod 644 "$M/policy.bin" "$M/policy.conf" "$M/uids.map" 2>/dev/null
  chmod 755 "$M/glm" 2>/dev/null
  chmod 700 "$D/sud" "$D/sud_policy" "$D/su_real" "$D/sudtest" \
            "$D/sud_gl" "$D/sud_gl_reap" "$D/pwn2" 2>/dev/null
  chmod 755 "$D/asuid" "$D/keylink" "$D/runroot.sh" "$D/busybox" 2>/dev/null
  chmod 755 "$D/glU1" "$D/mpnl" "$D/slideprobe" 2>/dev/null
  chmod 755 /data/local/tmp/glroot.sh "$D/gl_fix.sh" 2>/dev/null

  # ---- zombie pressure ------------------------------------------------------
  Z=$(ps -A -o STAT 2>/dev/null | grep -c '^Z')

  # ---- current daemon -------------------------------------------------------
  ours() { # $1 = pid
    [ "$(stat -c %u "/proc/$1" 2>/dev/null)" = "0" ] || return 1
    case "$(cat "/proc/$1/comm" 2>/dev/null)" in
      fsdbg|ddexe|smdexe|resetreason|atrace) ;;
      *) return 1 ;;
    esac
    case "$(tr '\0' ' ' < "/proc/$1/cmdline" 2>/dev/null)" in
      *"--daemon"*) return 0 ;;
      *) return 1 ;;
    esac
  }
  DPID=""
  for d in /proc/[0-9]*; do
    p=${d#/proc/}
    ours "$p" && { DPID=$p; break; }
  done

  NEED=0
  [ -z "$DPID" ] && NEED=1
  [ "$CMD" = "restart" ] && NEED=1
  [ "$Z" -gt "$ZMAX" ] && NEED=1
  echo "daemon_before=${DPID:-none} zombies=$Z need=$NEED"

  if [ "$NEED" = 1 ]; then
    for d in /proc/[0-9]*; do
      p=${d#/proc/}
      ours "$p" || continue
      echo "  kill $p [$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)]"
      kill -9 "$p" 2>/dev/null
    done
    sleep 1

    KL=""; SD=""
    for s in /system/bin/fsdbg /system/bin/smdexe /system/bin/ddexe /system/bin/resetreason /system/bin/atrace; do
      [ -x "$s" ] || continue
      if [ -z "$KL" ]; then KL="$s"
      elif [ -z "$SD" ] && [ "$s" != "$KL" ]; then SD="$s"; break; fi
    done

    SERHEX=""
    while read -r id fl us p1 p2 u g ty desc rest; do
      if [ "$ty" = "keyring" ] && [ "$desc" = "fscrypt:" ]; then SERHEX="$id"; fi
    done < /proc/keys
    if [ -n "$SERHEX" ]; then SER=$((0x$SERHEX)); else SER=0; fi

    if [ -n "$KL" ] && [ -n "$SD" ]; then
      mount --bind "$D/keylink" "$KL" 2>/dev/null || echo "  bind keylink FAILED"
      mount --bind "$D/sud_policy" "$SD" 2>/dev/null || echo "  bind sud FAILED"
      ( "$KL" "$SER" -- "$SD" --daemon >>/odm/ghostlock/daemon.log 2>&1 & )
      sleep 1
      umount -l "$KL" 2>/dev/null
      umount -l "$SD" 2>/dev/null
      echo "  started: kl=$KL sd=$SD ser=$SERHEX"
    elif [ -x /odm/bin/runroot.sh ]; then
      # No keylink blob on this install: DEFEX-bind the daemon over a
      # whitelisted system binary and start it straight from root.  The daemon
      # only needs /odm (tmpfs), so the missing fscrypt keyring does not matter.
      ( sh /odm/bin/runroot.sh "$D/sud" --daemon >>/odm/ghostlock/daemon.log 2>&1 & )
      sleep 1
      echo "  started via runroot (no keylink)"
    else
      echo "  NO SAFEPLACE TARGET"
    fi
    sleep 1
  fi

  # ---- final state ----------------------------------------------------------
  DPID=""
  for d in /proc/[0-9]*; do
    p=${d#/proc/}
    ours "$p" && { DPID=$p; break; }
  done
  SOCK=0
  grep -q 'sud-daemon' /proc/net/unix 2>/dev/null && SOCK=1
  SZ=$(wc -c < /odm/bin/su 2>/dev/null | tr -d ' ')
  POL=$(wc -c < /odm/ghostlock/policy.bin 2>/dev/null | tr -d ' ')
  [ -n "$POL" ] || POL=0
  RWN=$(wc -l < "$RWL" 2>/dev/null | tr -d ' ')
  [ -n "$RWN" ] || RWN=0
  RMNT=0
  if [ -f "$RWL" ]; then
    while IFS= read -r p; do
      [ -n "$p" ] && is_mnt "${p%/}" && RMNT=$((RMNT + 1))
    done < "$RWL"
  fi

  SUOK=0
  if [ "$SOCK" = 1 ] && [ -x /odm/bin/runroot.sh ]; then
    # asuid (drop-to-uid wrapper) is optional; without it probe as root, which
    # is what the policy allows anyway.
    if [ -x "$D/asuid" ]; then
      PROBE=$(sh /odm/bin/runroot.sh "$D/asuid" 2000 /odm/bin/su -c id 2>&1)
      PRC=$?
      echo "probe rc=$PRC out=[$PROBE]"
      case "$PROBE" in *"uid=0"*) SUOK=1 ;; esac
    else
      # No asuid: the helper is already uid 0 but in the kernel domain, and
      # Samsung DEFEX kills a direct exec of /odm/bin/su from there.  Readiness
      # is verified from uid 2000 instead (glroot --boot's su_live check).
      [ -x /odm/bin/su ] && SUOK=1
      echo "probe skipped (no asuid); su present=$( [ -x /odm/bin/su ] && echo yes || echo no )"
    fi
  fi
  BBOK=0
  [ -s /odm/bin/busybox ] && BBOK=1

  echo "daemon_after=${DPID:-none} socket=$SOCK su=${SZ:-0} policy=$POL mode=$MODE rw=$RWN mounts=$RMNT bb=$BBOK suok=$SUOK"
  printf 'ts=%s token=%s cmd=%s daemon=%s socket=%s su=%s policy=%s mode=%s rw=%s mounts=%s bb=%s suok=%s zombies=%s\n' \
    "$(date +%s)" "$TOKEN" "$CMD" "${DPID:-0}" "$SOCK" "${SZ:-0}" "$POL" "$MODE" "$RWN" "$RMNT" "$BBOK" "$SUOK" "$Z" > "$ST"
  chmod 644 "$ST" 2>/dev/null
  echo "=== helper done ==="
  cp "$LOG" /odm/ghostlock/helper.log 2>/dev/null
  chmod 644 /odm/ghostlock/helper.log 2>/dev/null
} >> "$LOG" 2>&1
