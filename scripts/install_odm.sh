#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Never remounts a partition, disables verity, changes PackageManager or starts
# the service. The operator must independently verify that /odm is outside the
# device's AVB/dm-verity coverage before using the explicit confirmation flag.
set -eu

MODE=preview
CONFIRMED=0
ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
BINARY=${QUASAR_BINARY:-"$ROOT/build/aarch64/quasar_openio"}
PACK_BINARY=${QUASAR_PACK_BINARY:-"$ROOT/build/aarch64/quasar_pack"}
PLUGIN_CONF=${QUASAR_PLUGIN_CONF:-"$ROOT/plugins/standard/plugin.conf"}
READELF=${QUASAR_READELF:-readelf}
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
[ "$MODE" = preview ] || [ "$(id -u)" = 0 ] || { echo 'root is required for --apply' >&2; exit 1; }
for file in "$BINARY" "$PACK_BINARY" "$PLUGIN_CONF" "$ROOT/init/quasar_openio.rc"; do
    [ -f "$file" ] || { echo "missing $file" >&2; exit 1; }
done
command -v "$READELF" >/dev/null 2>&1 || {
    echo "missing readelf; set QUASAR_READELF to a trusted implementation" >&2
    exit 1
}
verify_binary() {
    file=$1
    label=$2
    header=$("$READELF" -h "$file") || { echo "cannot inspect $label" >&2; exit 1; }
    case "$header" in *"Class:"*"ELF64"*) ;; *) echo "$label is not ELF64" >&2; exit 1 ;; esac
    case "$header" in *"Data:"*"little endian"*) ;; *) echo "$label is not little-endian" >&2; exit 1 ;; esac
    case "$header" in *"Machine:"*"AArch64"*) ;; *) echo "$label is not AArch64" >&2; exit 1 ;; esac
    program_headers=$("$READELF" -l "$file") || { echo "cannot inspect $label program headers" >&2; exit 1; }
    case "$program_headers" in
        *INTERP*) echo "$label has a dynamic interpreter; static binary required" >&2; exit 1 ;;
    esac
}
verify_binary "$BINARY" quasar_openio
verify_binary "$PACK_BINARY" quasar_pack
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
if command -v getprop >/dev/null 2>&1; then
    service_state=$(getprop init.svc.quasar_openio 2>/dev/null || true)
    case "$service_state" in
        running|restarting|stopping)
            echo "quasar_openio service is $service_state; stop it before replacing files" >&2
            exit 1
            ;;
    esac
fi

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
for dir in /odm/lib/quasar /odm/lib/quasar/plugins "$PLUGIN_DIR"; do
    chown 0:0 "$dir"
    chmod 0755 "$dir"
    chcon u:object_r:vendor_configs_file:s0 "$dir" || {
        echo "cannot label plugin directory $dir" >&2
        exit 1
    }
done
bin_tmp=/odm/bin/.quasar_openio.new
rc_tmp=/odm/etc/init/.quasar_openio.rc.new
pack_tmp=$PLUGIN_DIR/.quasar_pack.new
conf_tmp=$PLUGIN_DIR/.plugin.conf.new
bin_target=/odm/bin/quasar_openio
rc_target=/odm/etc/init/quasar_openio.rc
pack_target=$PLUGIN_DIR/quasar_pack
conf_target=$PLUGIN_DIR/plugin.conf
bin_old=/odm/bin/.quasar_openio.old
rc_old=/odm/etc/init/.quasar_openio.rc.old
pack_old=$PLUGIN_DIR/.quasar_pack.old
conf_old=$PLUGIN_DIR/.plugin.conf.old
staged="$bin_tmp $rc_tmp $pack_tmp $conf_tmp"
backups="$bin_old $rc_old $pack_old $conf_old"
# Staging paths are a fixed whitespace-separated list without spaces.
# shellcheck disable=SC2086
for file in $staged $backups; do
    if [ -e "$file" ] || [ -L "$file" ]; then
        echo "staging/backup file already exists: $file; inspect a previous interrupted install" >&2
        exit 1
    fi
done
cleanup_staged() {
    # shellcheck disable=SC2086
    for file in $staged; do
        rm -f "$file" || true
    done
}
bin_had=0 rc_had=0 pack_had=0 conf_had=0
bin_installed=0 rc_installed=0 pack_installed=0 conf_installed=0
rollback_install() {
    status=$?
    trap - EXIT HUP INT TERM
    echo 'install failed; restoring previous files' >&2
    [ "$conf_installed" = 0 ] || rm -f "$conf_target" || true
    [ "$conf_had" = 0 ] || mv "$conf_old" "$conf_target" || true
    [ "$pack_installed" = 0 ] || rm -f "$pack_target" || true
    [ "$pack_had" = 0 ] || mv "$pack_old" "$pack_target" || true
    [ "$rc_installed" = 0 ] || rm -f "$rc_target" || true
    [ "$rc_had" = 0 ] || mv "$rc_old" "$rc_target" || true
    [ "$bin_installed" = 0 ] || rm -f "$bin_target" || true
    [ "$bin_had" = 0 ] || mv "$bin_old" "$bin_target" || true
    cleanup_staged
    sync || true
    exit "$status"
}
trap rollback_install EXIT
trap 'exit 1' HUP INT TERM

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
# Re-check the exact staged bytes, not only the potentially mutable source paths.
verify_binary "$bin_tmp" staged_quasar_openio
verify_binary "$pack_tmp" staged_quasar_pack

if [ -e "$bin_target" ]; then
    bin_had=1
    mv "$bin_target" "$bin_old"
fi
mv "$bin_tmp" "$bin_target"
bin_installed=1
if [ -e "$rc_target" ]; then
    rc_had=1
    mv "$rc_target" "$rc_old"
fi
mv "$rc_tmp" "$rc_target"
rc_installed=1
if [ -e "$pack_target" ]; then
    pack_had=1
    mv "$pack_target" "$pack_old"
fi
mv "$pack_tmp" "$pack_target"
pack_installed=1
if [ -e "$conf_target" ]; then
    conf_had=1
    mv "$conf_target" "$conf_old"
fi
mv "$conf_tmp" "$conf_target"
conf_installed=1
sync
trap - EXIT HUP INT TERM
# The new set is durable. Old versions are now only rollback backups.
# shellcheck disable=SC2086
rm -f $backups
sync
ls -Z /odm/bin/quasar_openio /odm/etc/init/quasar_openio.rc "$PLUGIN_DIR/quasar_pack" "$PLUGIN_DIR/plugin.conf"
if [ -e /odm/bin/quasar_pack ] || [ -L /odm/bin/quasar_pack ] ||
   [ -e /odm/etc/init/quasar_pack.rc ] || [ -L /odm/etc/init/quasar_pack.rc ]; then
    echo 'NOTE: old standalone pack files remain; remove them manually after verification.'
fi
echo 'Installed only. Reboot/import semantics depend on the ROM; verify before starting.'
echo 'Manual start after verification: setprop ctl.start quasar_openio'
echo 'There is no separate quasar_pack init service; the supervisor launches the plugin.'
