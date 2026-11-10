#!/system/bin/sh
# payload.sh -- example /data payload executed as root through defex.
echo "payload: uid=$(id -u) ctx=$(cat /proc/self/attr/current)"
echo "payload: \$0=$0"
i=1
for a in "$@"; do
  echo "payload: arg$i=$a"
  i=$((i+1))
done
