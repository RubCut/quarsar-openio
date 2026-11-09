#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Never remounts a partition, disables verity, changes PackageManager or starts
# the service. The operator must independently verify that /odm is outside the
# device's AVB/dm-verity coverage before using the explicit confirmation flag.
#
# Apply is a stopped-daemon, hash-pinned, rollback-on-error file transaction.
# It is not a power-loss-atomic A/B update: backup names are printed so an
# interrupted transaction can be recovered manually before the next apply.
set -eu

MODE=preview
CONFIRMED=0
ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
BINARY=${QUASAR_BINARY:-"$ROOT/build/aarch64/quasar_openio"}
PACK_BINARY=${QUASAR_PACK_BINARY:-"$ROOT/build/aarch64/quasar_pack"}
PLUGIN_CONF=${QUASAR_PLUGIN_CONF:-"$ROOT/plugins/standard/plugin.conf"}
RC_SOURCE="$ROOT/init/quasar_openio.rc"
ODM_ROOT=/odm
PLUGIN_DIR="$ODM_ROOT/lib/quasar/plugins/standard"
BINARY_SHA256=${QUASAR_BINARY_SHA256:-}
PACK_SHA256=${QUASAR_PACK_BINARY_SHA256:-}
CONF_SHA256=${QUASAR_PLUGIN_CONF_SHA256:-}
RC_SHA256=${QUASAR_RC_SHA256:-}
STAGED=
BACKUPS=
TARGETS=
INSTALLED=
JOURNAL=

usage() {
    cat <<'EOF'
Usage: install_odm.sh [--preview|--apply --confirm-odm-unprotected]
Read-only preflight by default. No mount/verity/PackageManager changes.
Installs the supervisor and the standard plugin under /odm/lib/quasar/plugins/standard.

Apply requires all four SHA-256 pins from a trusted build/release:
  QUASAR_BINARY_SHA256, QUASAR_PACK_BINARY_SHA256,
  QUASAR_PLUGIN_CONF_SHA256, QUASAR_RC_SHA256
Binaries must be little-endian AArch64 ELF64 static PIE, no INTERP/DYNAMIC.
The daemon/plugin must be stopped first; this script never stops or starts it.
EOF
}

fail() { echo "$*" >&2; exit 1; }

sha256_of() {
    hash=$(sha256sum "$1") || return 1
    printf '%s\n' "${hash%% *}"
}

require_hash() {
    value=$1
    [ "${#value}" -eq 64 ] || return 1
    case "$value" in *[!0-9a-f]*) return 1 ;; *) return 0 ;; esac
}

daemon_running() {
    if command -v getprop >/dev/null 2>&1; then
        state=$(getprop init.svc.quasar_openio 2>/dev/null || true)
        case "$state" in running|restarting) return 0 ;; esac
    fi
    for cmdline in /proc/[0-9]*/cmdline; do
        [ -r "$cmdline" ] || continue
        first=$(tr '\0' '\n' < "$cmdline" 2>/dev/null | head -n 1) || continue
        case "$first" in
            quasar_openio|*/quasar_openio|quasar_pack|*/quasar_pack) return 0 ;;
        esac
    done
    return 1
}

verify_sources() {
    "$ROOT/scripts/verify_aarch64.sh" "$BINARY" "$BINARY_SHA256"
    "$ROOT/scripts/verify_aarch64.sh" "$PACK_BINARY" "$PACK_SHA256"
    actual=$(sha256_of "$PLUGIN_CONF")
    [ "$actual" = "$CONF_SHA256" ] || fail "plugin.conf SHA-256 mismatch: $actual"
    actual=$(sha256_of "$RC_SOURCE")
    [ "$actual" = "$RC_SHA256" ] || fail "init rc SHA-256 mismatch: $actual"
}

prepare_dirs() {
    for dir in "$ODM_ROOT/bin" "$ODM_ROOT/etc" "$ODM_ROOT/etc/init" \
               "$ODM_ROOT/lib" "$ODM_ROOT/lib/quasar" "$ODM_ROOT/lib/quasar/plugins" "$PLUGIN_DIR"; do
        [ ! -L "$dir" ] || fail "refusing symlinked install directory: $dir"
    done
    mkdir -p "$ODM_ROOT/bin" "$ODM_ROOT/etc/init" "$PLUGIN_DIR"
    TARGETS="$ODM_ROOT/bin/quasar_openio $PLUGIN_DIR/quasar_pack $PLUGIN_DIR/plugin.conf $ODM_ROOT/etc/init/quasar_openio.rc"
    for target in $TARGETS; do
        [ ! -L "$target" ] || fail "refusing symlinked install target: $target"
        if [ -e "$target" ]; then
            [ -f "$target" ] || fail "install target is not a regular file: $target"
        fi
    done
    JOURNAL="$ODM_ROOT/lib/quasar/.install-journal"
    [ ! -e "$JOURNAL" ] || fail "interrupted install journal exists: $JOURNAL; recover backups first"
}

stage_files() {
    for target in $TARGETS; do
        dir=${target%/*}
        base=${target##*/}
        stage="$dir/.$base.new.$$"
        backup="$dir/.$base.backup.$$"
        if [ -e "$stage" ] || [ -L "$stage" ]; then fail "staging file already exists: $stage"; fi
        if [ -e "$backup" ] || [ -L "$backup" ]; then fail "backup file already exists: $backup"; fi
        STAGED="$STAGED $stage"
        BACKUPS="$BACKUPS $backup"
    done
    set -- "$BINARY" "$PACK_BINARY" "$PLUGIN_CONF" "$RC_SOURCE"
    for stage in $STAGED; do
        src=$1
        shift
        cp "$src" "$stage"
        case "$stage" in
            */.quasar_openio.new.*)
                chmod 0755 "$stage"
                chcon u:object_r:quasar_openio_exec:s0 "$stage" || fail 'supervisor exec label failed; load policy first'
                ;;
            */.quasar_pack.new.*)
                chmod 0755 "$stage"
                chcon u:object_r:quasar_plugin_exec:s0 "$stage" || fail 'plugin exec label failed; load policy first'
                ;;
            */.plugin.conf.new.*|*/.quasar_openio.rc.new.*)
                chmod 0644 "$stage"
                chcon u:object_r:vendor_configs_file:s0 "$stage" || fail 'config label failed'
                ;;
            *) fail "unknown staging target: $stage" ;;
        esac
    done
    # Re-hash the staged copies: a source swap after preflight must not commit.
    # shellcheck disable=SC2086
    set -- $BINARY_SHA256 "$PACK_SHA256" "$CONF_SHA256" "$RC_SHA256"
    for stage in $STAGED; do
        expected=$1
        shift
        actual=$(sha256_of "$stage")
        [ "$actual" = "$expected" ] || fail "staged SHA-256 mismatch: $stage"
    done
}

nth() {
    n=$1
    shift
    for item in "$@"; do
        if [ "$n" -eq 1 ]; then printf '%s\n' "$item"; return 0; fi
        n=$((n - 1))
    done
    return 1
}

rollback() {
    status=0
    # shellcheck disable=SC2086
    set -- $TARGETS
    index=1
    for target in "$@"; do
        # shellcheck disable=SC2086
        backup=$(nth "$index" $BACKUPS)
        if [ -e "$backup" ] || [ -L "$backup" ]; then
            mv -f "$backup" "$target" || { echo "ROLLBACK FAILED: restore $backup -> $target" >&2; status=1; }
        else
            case " $INSTALLED " in
                *" $target "*) rm -f "$target" || { echo "ROLLBACK FAILED: remove $target" >&2; status=1; } ;;
            esac
        fi
        index=$((index + 1))
    done
    # shellcheck disable=SC2086
    for stage in $STAGED; do rm -f "$stage" || true; done
    if [ "$status" -eq 0 ]; then
        [ -z "$JOURNAL" ] || rm -f "$JOURNAL" || true
    else
        echo 'Manual recovery required; backup paths are listed above.' >&2
    fi
    return "$status"
}

cleanup_staged() {
    # shellcheck disable=SC2086
    for stage in $STAGED; do rm -f "$stage" || true; done
}

commit_files() {
    printf 'pid=%s\ntargets=%s\nbackups=%s\nstaged=%s\n' "$$" "$TARGETS" "$BACKUPS" "$STAGED" > "$JOURNAL"
    trap 'rollback; exit 1' EXIT
    index=1
    for target in $TARGETS; do
        # shellcheck disable=SC2086
        stage=$(nth "$index" $STAGED)
        # shellcheck disable=SC2086
        backup=$(nth "$index" $BACKUPS)
        if [ -e "$target" ]; then
            mv "$target" "$backup"
        fi
        mv "$stage" "$target"
        INSTALLED="$INSTALLED $target"
        index=$((index + 1))
    done
    sync
    trap - EXIT
    rm -f "$JOURNAL" || echo "WARNING: could not remove install journal $JOURNAL" >&2
    # shellcheck disable=SC2086
    for backup in $BACKUPS; do
        if [ -e "$backup" ] || [ -L "$backup" ]; then rm -f "$backup" || true; fi
    done
}

main() {
    for arg in "$@"; do
        case "$arg" in
            --preview) MODE=preview ;;
            --apply) MODE=apply ;;
            --confirm-odm-unprotected) CONFIRMED=1 ;;
            --help|-h) usage; exit 0 ;;
            *) echo 'unknown option; use --help' >&2; exit 2 ;;
        esac
    done
    for file in "$BINARY" "$PACK_BINARY" "$PLUGIN_CONF" "$RC_SOURCE"; do
        [ -f "$file" ] || fail "missing $file"
    done
    resolved=$(readlink -f "$ODM_ROOT") || fail "$ODM_ROOT does not exist"
    [ "$resolved" = "$ODM_ROOT" ] || fail "$ODM_ROOT aliases another partition; refusing"

    # mountinfo: mountpoint is field 5, options field 6; filesystem follows '-'.
    found=0 writable=0 filesystem='' source=''
    while read -r _mid _parent _dev _rootpoint mountpoint options rest; do
        [ "$mountpoint" = "$ODM_ROOT" ] || continue
        found=1
        writable=0
        case ",$options," in *,rw,*) writable=1 ;; esac
        # shellcheck disable=SC2086
        set -- $rest
        while [ "$#" -gt 0 ] && [ "$1" != '-' ]; do shift; done
        [ "$#" -ge 3 ] || fail 'cannot parse mountinfo'
        filesystem=$2
        source=$3
    done < /proc/self/mountinfo
    if [ "$found" != 1 ] || [ "$writable" != 1 ]; then
        fail "$ODM_ROOT is not a separate writable mount; refusing (no remount attempted)"
    fi
    case "$filesystem" in ext4|f2fs) ;; *) fail "unsupported $ODM_ROOT filesystem: $filesystem" ;; esac
    case "$source" in /dev/block/*) ;; *) fail "unexpected $ODM_ROOT mount source; refusing overlay" ;; esac

    echo "$ODM_ROOT: source=$source filesystem=$filesystem mount=rw"
    echo 'WARNING: rw does NOT prove that dm-verity/AVB does not cover this partition.'
    echo 'The quasar_openio/quasar_plugin SELinux domains/exec types must already be loaded.'
    if [ "$MODE" = preview ]; then
        echo "DRY RUN: would install $BINARY -> $ODM_ROOT/bin/quasar_openio"
        echo "DRY RUN: would install $RC_SOURCE -> $ODM_ROOT/etc/init/quasar_openio.rc"
        echo "DRY RUN: would install $PACK_BINARY -> $PLUGIN_DIR/quasar_pack"
        echo "DRY RUN: would install $PLUGIN_CONF -> $PLUGIN_DIR/plugin.conf"
        echo 'DRY RUN: labels quasar_openio_exec / quasar_plugin_exec / vendor_configs_file; no service start'
        echo "DRY RUN: candidate SHA-256:"
        bin_hash=$(sha256_of "$BINARY")
        pack_hash=$(sha256_of "$PACK_BINARY")
        printf '%s  %s\n' "$bin_hash" "$BINARY"
        printf '%s  %s\n' "$pack_hash" "$PACK_BINARY"
        sha256_of "$PLUGIN_CONF"
        sha256_of "$RC_SOURCE"
        "$ROOT/scripts/verify_aarch64.sh" "$BINARY" "$bin_hash"
        "$ROOT/scripts/verify_aarch64.sh" "$PACK_BINARY" "$pack_hash"
        exit 0
    fi
    [ "$(id -u)" = 0 ] || fail 'root is required for --apply'
    [ "$CONFIRMED" = 1 ] || fail 'Apply requires --confirm-odm-unprotected after independent verity verification'
    require_hash "$BINARY_SHA256" || fail 'QUASAR_BINARY_SHA256 must be a 64-hex pin'
    require_hash "$PACK_SHA256" || fail 'QUASAR_PACK_BINARY_SHA256 must be a 64-hex pin'
    require_hash "$CONF_SHA256" || fail 'QUASAR_PLUGIN_CONF_SHA256 must be a 64-hex pin'
    require_hash "$RC_SHA256" || fail 'QUASAR_RC_SHA256 must be a 64-hex pin'
    if daemon_running; then
        fail 'quasar_openio/quasar_pack is running; stop it manually and verify before installing'
    fi
    verify_sources
    prepare_dirs
    stage_files
    trap cleanup_staged EXIT
    commit_files
    trap - EXIT
    ls -Z "$ODM_ROOT/bin/quasar_openio" "$ODM_ROOT/etc/init/quasar_openio.rc" "$PLUGIN_DIR/quasar_pack" "$PLUGIN_DIR/plugin.conf"
    if [ -e "$ODM_ROOT/bin/quasar_pack" ] || [ -L "$ODM_ROOT/bin/quasar_pack" ] ||
       [ -e "$ODM_ROOT/etc/init/quasar_pack.rc" ] || [ -L "$ODM_ROOT/etc/init/quasar_pack.rc" ]; then
        echo 'NOTE: old standalone pack files remain; remove them manually after verification.'
    fi
    echo 'Installed only. Reboot/import semantics depend on the ROM; verify before starting.'
    echo 'Manual start after verification: setprop ctl.start quasar_openio'
    echo 'There is no separate quasar_pack init service; the supervisor launches the plugin.'
}

# Test hook: source this file to exercise the transaction functions with mock
# chcon/sha256sum and a temporary ODM_ROOT. Executing with the hook changes nothing.
if [ "${QUASAR_INSTALL_LIB_ONLY:-0}" = 1 ]; then
    # shellcheck disable=SC2317
    return 0 2>/dev/null || exit 0
fi
main "$@"
