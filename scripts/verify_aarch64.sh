#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Read-only ELF preflight for the /odm installer. Never executes the candidate.
# Requires a little-endian AArch64 ELF64 ET_DYN (static PIE), no PT_INTERP and
# no DT_NEEDED, plus an operator-supplied SHA-256. Uses only od/dd/wc/sha256sum
# so it can run on Android without readelf/file/Python.
set -eu

usage() {
    echo 'Usage: verify_aarch64.sh FILE EXPECTED_SHA256' >&2
    exit 2
}

[ "$#" -eq 2 ] || usage
FILE=$1
EXPECTED=$2
[ "${#EXPECTED}" -eq 64 ] || { echo 'expected SHA-256 must be 64 lowercase hex characters' >&2; exit 2; }
case "$EXPECTED" in *[!0-9a-f]*) echo 'expected SHA-256 must be lowercase hex' >&2; exit 2 ;; esac
[ -f "$FILE" ] || { echo "not a regular file: $FILE" >&2; exit 1; }
[ ! -L "$FILE" ] || { echo "refusing symlink: $FILE" >&2; exit 1; }
for tool in od dd wc sha256sum; do
    command -v "$tool" >/dev/null 2>&1 || { echo "missing required tool: $tool" >&2; exit 1; }
done

# shellcheck disable=SC2046
set -- $(wc -c < "$FILE")
SIZE=$1
[ "$SIZE" -ge 64 ] || { echo 'truncated ELF header' >&2; exit 1; }

# od -tu1 gives decimal bytes; positional parameters are the 64 header bytes.
# shellcheck disable=SC2046
set -- $(od -An -tu1 -N64 "$FILE")
[ "$#" -eq 64 ] || { echo 'cannot read ELF header' >&2; exit 1; }
if [ "$1" -ne 127 ] || [ "$2" -ne 69 ] || [ "$3" -ne 76 ] || [ "$4" -ne 70 ]; then
    echo 'not an ELF file' >&2; exit 1
fi
[ "$5" -eq 2 ] || { echo 'not ELF64' >&2; exit 1; }
[ "$6" -eq 1 ] || { echo 'not little-endian' >&2; exit 1; }
[ "$7" -eq 1 ] || { echo 'bad ELF ident version' >&2; exit 1; }
ETYPE=$(( ${17} + ${18} * 256 ))
MACHINE=$(( ${19} + ${20} * 256 ))
[ "$MACHINE" -eq 183 ] || { echo "not AArch64 (e_machine=$MACHINE)" >&2; exit 1; }
[ "$ETYPE" -eq 3 ] || { echo "not ET_DYN/static-PIE (e_type=$ETYPE)" >&2; exit 1; }
if [ "${21}" -ne 1 ] || [ "${22}" -ne 0 ] || [ "${23}" -ne 0 ] || [ "${24}" -ne 0 ]; then
    echo 'bad ELF version' >&2; exit 1
fi

# Reject program-header offsets that do not fit in 32 bits; the file itself is
# far smaller, so this is a malformed/hostile ELF for our purposes.
if [ "${37}" -ne 0 ] || [ "${38}" -ne 0 ] || [ "${39}" -ne 0 ] || [ "${40}" -ne 0 ]; then
    echo 'unsupported e_phoff' >&2; exit 1
fi
PHOFF=$(( ${33} + ${34} * 256 + ${35} * 65536 + ${36} * 16777216 ))
PHENTSIZE=$(( ${55} + ${56} * 256 ))
PHNUM=$(( ${57} + ${58} * 256 ))
[ "$PHOFF" -ge 64 ] || { echo 'bad e_phoff' >&2; exit 1; }
[ "$PHENTSIZE" -ge 56 ] || { echo 'bad e_phentsize' >&2; exit 1; }
if [ "$PHNUM" -le 0 ] || [ "$PHNUM" -gt 128 ]; then echo 'bad e_phnum' >&2; exit 1; fi
[ "$((PHOFF + PHNUM * PHENTSIZE))" -le "$SIZE" ] || {
    echo 'program headers outside file' >&2; exit 1;
}

i=0
while [ "$i" -lt "$PHNUM" ]; do
    offset=$((PHOFF + i * PHENTSIZE))
    # shellcheck disable=SC2046
    set -- $(dd if="$FILE" bs=1 skip="$offset" count=56 2>/dev/null | od -An -tu1)
    [ "$#" -eq 56 ] || { echo 'cannot read program header' >&2; exit 1; }
    ptype=$(( $1 + $2 * 256 + $3 * 65536 + $4 * 16777216 ))
    [ "$ptype" -ne 3 ] || { echo 'PT_INTERP present: dynamic executable' >&2; exit 1; }
    if [ "$ptype" -eq 2 ]; then
        # Static PIE may carry PT_DYNAMIC for relative relocations, but it must
        # not depend on any shared object (DT_NEEDED). Parse the dynamic tags.
        if [ "${13}" -ne 0 ] || [ "${14}" -ne 0 ] || [ "${15}" -ne 0 ] || [ "${16}" -ne 0 ] ||
           [ "${37}" -ne 0 ] || [ "${38}" -ne 0 ] || [ "${39}" -ne 0 ] || [ "${40}" -ne 0 ]; then
            echo 'unsupported 64-bit dynamic offset/size' >&2; exit 1
        fi
        dynoff=$(( ${9} + ${10} * 256 + ${11} * 65536 + ${12} * 16777216 ))
        dynsize=$(( ${33} + ${34} * 256 + ${35} * 65536 + ${36} * 16777216 ))
        if [ "$dynsize" -gt "$SIZE" ] || [ "$((dynoff + dynsize))" -gt "$SIZE" ]; then
            echo 'dynamic section outside file' >&2; exit 1
        fi
        [ "$((dynsize % 16))" -eq 0 ] || { echo 'bad dynamic entry size' >&2; exit 1; }
        entries=$((dynsize / 16))
        [ "$entries" -le 4096 ] || { echo 'dynamic section too large' >&2; exit 1; }
        j=0
        while [ "$j" -lt "$entries" ]; do
            doffset=$((dynoff + j * 16))
            # shellcheck disable=SC2046
            set -- $(dd if="$FILE" bs=1 skip="$doffset" count=8 2>/dev/null | od -An -tu1)
            [ "$#" -eq 8 ] || { echo 'cannot read dynamic tag' >&2; exit 1; }
            tag=$(( $1 + $2 * 256 + $3 * 65536 + $4 * 16777216 ))
            [ "$tag" -ne 1 ] || { echo 'DT_NEEDED present: not statically linked' >&2; exit 1; }
            if [ "$5" -ne 0 ] || [ "$6" -ne 0 ] || [ "$7" -ne 0 ] || [ "$8" -ne 0 ]; then
                echo 'unsupported 64-bit dynamic tag' >&2; exit 1
            fi
            j=$((j + 1))
        done
    fi
    i=$((i + 1))
done

hash=$(sha256sum "$FILE")
actual=${hash%% *}
[ "$actual" = "$EXPECTED" ] || {
    echo "SHA-256 mismatch for $FILE: got $actual, expected $EXPECTED" >&2
    exit 1
}
echo "verified AArch64 static PIE without INTERP/NEEDED: $FILE ($actual)"
