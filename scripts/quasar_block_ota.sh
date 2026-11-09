#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# One-shot administration script, NOT a daemon/event-loop command runner.
# Default is dry-run. iptables resolves names ONCE; CDN addresses can change.
set -eu

MODE=preview
IPV4_ONLY=0
UIDS=
MARKER=quasar-openio-ota-v1
DOMAINS="download.cdn.yandex.net updater.mobile.yandex.net quasar.s3.yandex.net"
IPT=${QUASAR_IPTABLES:-iptables}
IPT6=${QUASAR_IP6TABLES:-ip6tables}
IPV6_STATE=${QUASAR_IPV6_DISABLED_FILE:-/proc/sys/net/ipv6/conf/all/disable_ipv6}

usage() {
    cat <<'EOF'
Usage: quasar_block_ota.sh [--preview|--apply|--remove|--status] [--uid UID]... [--ipv4-only]
  --preview     print the plan without changing firewall rules (default)
  --apply       install both IPv4 and IPv6 rules; roll back new chains on error
  --remove      remove only chains/jumps carrying quasar-openio-ota-v1
  --status      show this script's rules
  --uid UID     additionally block an entire updater UID (repeatable, max 4)
  --ipv4-only   permitted only when kernel IPv6 is verifiably disabled
Domain rules are address snapshots, not persistent DNS filtering. A shared CDN
address can also serve unrelated content. Prefer a verified updater --uid rule.
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --preview|--apply|--remove|--status) MODE=${1#--} ;;
        --ipv4-only) IPV4_ONLY=1 ;;
        --uid)
            [ "$#" -ge 2 ] || { usage >&2; exit 2; }
            case "$2" in ''|*[!0-9]*) echo 'UID must be numeric' >&2; exit 2 ;; esac
            [ "$2" -le 4294967294 ] || exit 2
            count=0
            for uid in $UIDS; do count=$((count + 1)); done
            [ "$count" -lt 4 ] || { echo 'at most four UID rules' >&2; exit 2; }
            case " $UIDS " in *" $2 "*) ;; *) UIDS="$UIDS $2" ;; esac
            shift
            ;;
        --help|-h) usage; exit 0 ;;
        *) usage >&2; exit 2 ;;
    esac
    shift
done

if [ "$MODE" = preview ]; then
    echo 'DRY RUN: no firewall changes'
    for domain in $DOMAINS; do
        printf '%s -A QIO_OTA_NEW -d %s -m comment --comment %s -j DROP\n' "$IPT" "$domain" "$MARKER"
        [ "$IPV4_ONLY" = 1 ] || printf '%s -A QIO_OTA_NEW -d %s -m comment --comment %s -j DROP\n' "$IPT6" "$domain" "$MARKER"
    done
    for uid in $UIDS; do
        printf '%s -A QIO_OTA_NEW -m owner --uid-owner %s -m comment --comment %s -j DROP\n' "$IPT" "$uid" "$MARKER"
        [ "$IPV4_ONLY" = 1 ] || printf '%s -A QIO_OTA_NEW -m owner --uid-owner %s -m comment --comment %s -j DROP\n' "$IPT6" "$uid" "$MARKER"
    done
    echo 'Apply inserts a jump at OUTPUT position 1 only after the new chain is populated.'
    exit 0
fi

[ "$(id -u)" = 0 ] || { echo 'root is required' >&2; exit 1; }
command -v "$IPT" >/dev/null 2>&1 || { echo "missing $IPT" >&2; exit 1; }
if [ "$IPV4_ONLY" = 1 ]; then
    state=
    [ -r "$IPV6_STATE" ] && IFS= read -r state < "$IPV6_STATE"
    [ "$state" = 1 ] || { echo '--ipv4-only requires verified disable_ipv6=1' >&2; exit 1; }
else
    command -v "$IPT6" >/dev/null 2>&1 || {
        echo "missing $IPT6; refusing an IPv4-only bypass" >&2; exit 1;
    }
fi

families() { if [ "$IPV4_ONLY" = 1 ]; then echo 4; else echo '4 6'; fi; }
tool_for() { case "$1" in 4) printf '%s\n' "$IPT" ;; 6) printf '%s\n' "$IPT6" ;; *) return 1 ;; esac; }
old_for() { case "$1" in 4) printf '%s\n' "$OLD4" ;; 6) printf '%s\n' "$OLD6" ;; *) return 1 ;; esac; }
created_for() { case "$1" in 4) printf '%s\n' "$CREATED4" ;; 6) printf '%s\n' "$CREATED6" ;; *) return 1 ;; esac; }
inserted_for() { case "$1" in 4) printf '%s\n' "$INSERTED4" ;; 6) printf '%s\n' "$INSERTED6" ;; *) return 1 ;; esac; }
set_created() { case "$1" in 4) CREATED4=1 ;; 6) CREATED6=1 ;; esac; }
set_inserted() { case "$1" in 4) INSERTED4=1 ;; 6) INSERTED6=1 ;; esac; }

# Ownership is proven by a marked OUTPUT jump plus a non-empty chain whose every
# rule carries the marker. An empty QIO_OTA_* chain has no provenance and is never
# touched, even if its name looks like ours. Any foreign reference is refused.
has_marker() {
    case " $1 " in
        *" --comment $MARKER "*|*" --comment \"$MARKER\" "*) return 0 ;;
        *) return 1 ;;
    esac
}

valid_chain_name() {
    case "$1" in QIO_OTA_?*) ;; *) return 1 ;; esac
    case "$1" in *[!A-Za-z0-9_]*) return 1 ;; *) return 0 ;; esac
}

owned_chains() {
    tool=$1
    listing=$("$tool" -S) || return 1
    names=
    candidates=
    while IFS= read -r line; do
        case "$line" in
            -N\ QIO_OTA_*)
                name=${line#-N }
                valid_chain_name "$name" || return 1
                candidates="$candidates $name"
                ;;
            -A\ OUTPUT\ *-j\ QIO_OTA_*)
                has_marker "$line" || return 1
                target=${line##* -j }
                valid_chain_name "$target" || return 1
                case " $names " in *" $target "*) ;; *) names="$names $target" ;; esac
                ;;
            -A\ *\ -j\ QIO_OTA_*)
                return 1
                ;;
            -A\ QIO_OTA_*)
                has_marker "$line" || return 1
                ;;
        esac
    done <<EOF
$listing
EOF
    for name in $candidates; do
        case " $names " in *" $name "*) ;; *) return 1 ;; esac
    done
    for name in $names; do
        chain_listing=$("$tool" -S "$name") || return 1
        rules=0
        while IFS= read -r line; do
            case "$line" in
                -A\ "$name"\ *)
                    has_marker "$line" || return 1
                    rules=$((rules + 1))
                    ;;
            esac
        done <<EOF
$chain_listing
EOF
        [ "$rules" -gt 0 ] || return 1
    done
    printf '%s\n' "$names"
}

remove_chains() {
    tool=$1
    names=$2
    for name in $names; do
        n=0
        while "$tool" -C OUTPUT -m comment --comment "$MARKER" -j "$name" 2>/dev/null; do
            "$tool" -D OUTPUT -m comment --comment "$MARKER" -j "$name"
            n=$((n + 1))
            [ "$n" -lt 64 ] || { echo 'too many duplicate jumps; inspect firewall' >&2; return 1; }
        done
        if "$tool" -S "$name" >/dev/null 2>&1; then
            "$tool" -F "$name"
            "$tool" -X "$name"
        fi
    done
}

OLD4=
OLD6=
for family in $(families); do
    tool=$(tool_for "$family")
    names=$(owned_chains "$tool") || { echo 'foreign/unsafe QIO_OTA chain collision; no changes' >&2; exit 1; }
    if [ "$family" = 4 ]; then OLD4=$names; else OLD6=$names; fi
done

if [ "$MODE" = remove ]; then
    for family in $(families); do remove_chains "$(tool_for "$family")" "$(old_for "$family")"; done
    echo 'Quasar OTA rules removed; no other chains were flushed'
    exit 0
fi
if [ "$MODE" = status ]; then
    for family in $(families); do
        tool=$(tool_for "$family")
        names=$(old_for "$family")
        for name in $names; do "$tool" -S "$name"; "$tool" -S OUTPUT; done
    done
    exit 0
fi

CHAIN="QIO_OTA_$$"
for family in $(families); do
    tool=$(tool_for "$family")
    if "$tool" -S "$CHAIN" >/dev/null 2>&1; then
        echo "chain $CHAIN already exists; run --remove and retry" >&2
        exit 1
    fi
done
CREATED4=0 CREATED6=0 INSERTED4=0 INSERTED6=0
rollback() {
    for family in $(families); do
        tool=$(tool_for "$family")
        inserted=$(inserted_for "$family")
        created=$(created_for "$family")
        if [ "$inserted" = 1 ]; then
            "$tool" -D OUTPUT -m comment --comment "$MARKER" -j "$CHAIN" || true
        fi
        if [ "$created" = 1 ]; then
            "$tool" -F "$CHAIN" || true
            "$tool" -X "$CHAIN" || true
        fi
    done
}
fail_apply() {
    trap - EXIT
    echo 'firewall command failed; rolling back new chains (previous rules retained)' >&2
    rollback
    exit 1
}
trap fail_apply EXIT
for family in $(families); do
    tool=$(tool_for "$family")
    "$tool" -N "$CHAIN"
    set_created "$family"
    for domain in $DOMAINS; do
        "$tool" -A "$CHAIN" -d "$domain" -m comment --comment "$MARKER" -j DROP
    done
    for uid in $UIDS; do
        "$tool" -A "$CHAIN" -m owner --uid-owner "$uid" -m comment --comment "$MARKER" -j DROP
    done
done
for family in $(families); do
    "$(tool_for "$family")" -I OUTPUT 1 -m comment --comment "$MARKER" -j "$CHAIN"
    set_inserted "$family"
done
# New rules are active before old rules are removed: no replacement gap.
for family in $(families); do
    names=$(old_for "$family")
    remove_chains "$(tool_for "$family")" "$names" || {
        echo 'WARNING: old-chain cleanup failed; the new protective jump remains active' >&2
    }
done
trap - EXIT
echo 'OTA address rules installed for both requested families.'
echo 'WARNING: DNS/CDN addresses change; this is not a permanent domain-name filter.'
echo 'Existing sockets and already downloaded packages are not removed.'
