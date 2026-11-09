#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Never remounts a partition, disables verity, changes PackageManager or starts
# the service. The operator must independently verify that /odm is outside the
# device's AVB/dm-verity coverage before using the explicit confirmation flag.
set -eu

MODE=preview
CONFIRMED=0
ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
BINARY=${QUASAR_BINARY:-"$ROOT/build/static/quasar_openio"}
PACK_BINARY=${QUASAR_PACK_BINARY:-"$ROOT/build/static/quasar_pack"}
PLUGIN_CONF=${QUASAR_PLUGIN_CONF:-"$ROOT/plugins/standard/plugin.conf"}
PLUGIN_DIR=/odm/lib/quasar/plugins/standard

for arg in "$@"; do
    case "$arg" in
        --preview) MODE=preview ;;
        --apply) MODE=apply ;;
        --confirm-odm-unprotected) CONFIRMED=1 ;;
        --help|-h)
            echo 'Usage: install_odm.sh [--preview|--apply --confirm-odm-unprotected]'
            echo 'Read-only preflight by default. No mount/verity/PackageManager changes.'
            echo 'Installs the supervisor and the standard plugin under /odm/lib/quasar/plugins/standard.'
            exit 0
            ;;
        *) echo 'unknown option; use --help' >&2; exit 2 ;;
    esac
done
[ "$(id -u)" = 0 ] || { echo 'root is required' >&2; exit 1; }
for file in "$BINARY" "$PACK_BINARY" "$PLUGIN_CONF" "$ROOT/init/quasar_openio.rc"; do
    [ -f "$file" ] || { echo "missing $file" >&2; exit 1; }
done
resolved=$(readlink -f /odm) || { echo '/odm does not exist' >&2; exit 1; }
[ "$resolved" = /odm ] || { echo '/odm aliases another partition; refusing' >&2; exit 1; }

# mountinfo: mountpoint is field 5, options field 6; filesystem follows '-'.
found=0 writable=0 filesystem='' source=''
while read -r _mid _parent _dev _rootpoint mountpoint options rest; do
    [ "$mountpoint" = /odm ] || continue
    found=1
    writable=0
    case ",$options," in *,rw,*) writable=1 ;; esac
    # mountinfo optional fields are whitespace-separated by specification.
    # shellcheck disable=SC2086
    set -- $rest
    while [ "$#" -gt 0 ] && [ "$1" != '-' ]; do shift; done
    [ "$#" -ge 3 ] || { echo 'cannot parse mountinfo' >&2; exit 1; }
    filesystem=$2
    source=$3
done < /proc/self/mountinfo
if [ "$found" != 1 ] || [ "$writable" != 1 ]; then
    echo '/odm is not a separate writable mount; refusing (no remount attempted)' >&2
    exit 1
fi
case "$filesystem" in ext4|f2fs) ;; *) echo "unsupported /odm filesystem: $filesystem" >&2; exit 1 ;; esac
case "$source" in /dev/block/*) ;; *) echo 'unexpected /odm mount source; refusing overlay' >&2; exit 1 ;; esac

echo "/odm: source=$source filesystem=$filesystem mount=rw"
echo 'WARNING: rw does NOT prove that dm-verity/AVB does not cover this partition.'
echo 'The quasar_openio SELinux domain/exec type must already be loaded.'
if [ "$MODE" = preview ]; then
    echo "DRY RUN: would install $BINARY -> /odm/bin/quasar_openio"
    echo 'DRY RUN: would install init/quasar_openio.rc -> /odm/etc/init/quasar_openio.rc'
    echo "DRY RUN: would install $PACK_BINARY -> $PLUGIN_DIR/quasar_pack"
    echo "DRY RUN: would install $PLUGIN_CONF -> $PLUGIN_DIR/plugin.conf"
    echo 'DRY RUN: rc/conf label u:object_r:vendor_configs_file:s0; binaries quasar_openio_exec; no service start'
    exit 0
fi
[ "$CONFIRMED" = 1 ] || {
    echo 'Apply requires --confirm-odm-unprotected after independent verity verification' >&2
    exit 1
}

for dir in /odm/bin /odm/etc /odm/etc/init /odm/lib /odm/lib/quasar /odm/lib/quasar/plugins "$PLUGIN_DIR"; do
    if [ -L "$dir" ]; then
        echo "refusing symlinked install directory: $dir" >&2
        exit 1
    fi
done
for target in /odm/bin/quasar_openio /odm/etc/init/quasar_openio.rc "$PLUGIN_DIR/quasar_pack" "$PLUGIN_DIR/plugin.conf"; do
    if [ -L "$target" ]; then
        echo "refusing symlinked install target: $target" >&2
        exit 1
    fi
done
mkdir -p /odm/bin /odm/etc/init "$PLUGIN_DIR"
bin_tmp=/odm/bin/.quasar_openio.new
rc_tmp=/odm/etc/init/.quasar_openio.rc.new
pack_tmp=$PLUGIN_DIR/.quasar_pack.new
conf_tmp=$PLUGIN_DIR/.plugin.conf.new
staged="$bin_tmp $rc_tmp $pack_tmp $conf_tmp"
# Staging paths are a fixed whitespace-separated list without spaces.
# shellcheck disable=SC2086
for file in $staged; do
    if [ -e "$file" ] || [ -L "$file" ]; then
        echo "staging file already exists: $file" >&2
        exit 1
    fi
done
cleanup_staged() {
    # shellcheck disable=SC2086
    for file in $staged; do
        rm -f "$file"
    done
}
trap cleanup_staged EXIT

cp "$BINARY" "$bin_tmp"
chmod 0755 "$bin_tmp"
chcon u:object_r:quasar_openio_exec:s0 "$bin_tmp" || {
    echo 'Custom exec label failed; load the reference SELinux policy first' >&2
    exit 1
}
cp "$ROOT/init/quasar_openio.rc" "$rc_tmp"
chmod 0644 "$rc_tmp"
chcon u:object_r:vendor_configs_file:s0 "$rc_tmp" || exit 1

cp "$PACK_BINARY" "$pack_tmp"
chmod 0755 "$pack_tmp"
chcon u:object_r:quasar_openio_exec:s0 "$pack_tmp" || {
    echo 'Custom exec label failed for quasar_pack; load the reference SELinux policy first' >&2
    exit 1
}
cp "$PLUGIN_CONF" "$conf_tmp"
chmod 0644 "$conf_tmp"
chcon u:object_r:vendor_configs_file:s0 "$conf_tmp" || exit 1

mv "$bin_tmp" /odm/bin/quasar_openio
mv "$rc_tmp" /odm/etc/init/quasar_openio.rc
mv "$pack_tmp" "$PLUGIN_DIR/quasar_pack"
mv "$conf_tmp" "$PLUGIN_DIR/plugin.conf"
trap - EXIT
sync
ls -Z /odm/bin/quasar_openio /odm/etc/init/quasar_openio.rc "$PLUGIN_DIR/quasar_pack" "$PLUGIN_DIR/plugin.conf"
if [ -e /odm/bin/quasar_pack ] || [ -L /odm/bin/quasar_pack ] ||
   [ -e /odm/etc/init/quasar_pack.rc ] || [ -L /odm/etc/init/quasar_pack.rc ]; then
    echo 'NOTE: old standalone pack files remain; remove them manually after verification.'
fi
echo 'Installed only. Reboot/import semantics depend on the ROM; verify before starting.'
echo 'Manual start after verification: setprop ctl.start quasar_openio'
echo 'There is no separate quasar_pack init service; the supervisor launches the plugin.'
