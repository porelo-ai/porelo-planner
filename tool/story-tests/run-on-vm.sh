#!/usr/bin/env bash
# Runs one story test on the test machine, from the host: sends a Git bundle of the tested commit and the native
# executable built from it into a run directory of the machine's account, runs the story's script there, takes its
# report back, and removes the run directory, on an interrupt too.
#
# Usage: run-on-vm.sh <story> --vm USER@HOST --key FILE [--executable FILE] [--expect N=ISSUE]...
#
#   <story>            the story's script in this directory without .sh, as st309
#   --vm USER@HOST     the test machine's account
#   --key FILE         the SSH key that reaches it
#   --executable FILE  the native executable built from the tested commit; planner/app/target/porelo-planner
#                      when absent
#   --expect N=ISSUE   handed to the story's script: step N fails until ISSUE is built
#
# The tested commit is HEAD of the checkout this script lies in, which must have no uncommitted change to a tracked
# file. The report is printed and kept in target/story-tests/<story>.log.
#
# Exit codes: the story script's (0 passed, 1 a step failed, 2 the run could not be made, 3 its clean-up left
# something), 2 when this script cannot run, 3 when the run directory or another leftover stays on the machine.

set -euo pipefail

readonly NAME=${0##*/}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
readonly HERE
ROOT=$(git -C "$HERE" rev-parse --show-toplevel)
readonly ROOT

die() {
    printf '%s: %s\n' "$NAME" "$1" >&2
    exit 2
}

story=${1:-}
[[ $story =~ ^st[0-9]+$ && -f $HERE/$story.sh ]] || die "usage: $NAME <story> --vm USER@HOST --key FILE; no script $story.sh"
shift
vm=
key=
exe=$ROOT/planner/app/target/porelo-planner
expects=()
while (($#)); do
    case $1 in
    --vm)
        vm=${2:-}
        shift 2
        ;;
    --key)
        key=${2:-}
        shift 2
        ;;
    --executable)
        exe=${2:-}
        shift 2
        ;;
    --expect)
        [[ ${2:-} =~ ^([0-9]+|story)=[A-Z]{1,4}[0-9]{1,5}$ ]] || die "--expect takes N=ISSUE, got '${2:-}'"
        expects+=(--expect "$2")
        shift 2
        ;;
    *) die "unknown argument '$1'" ;;
    esac
done
[[ $vm =~ ^[a-z_][a-z0-9_-]{0,31}@[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]] || die "--vm must be USER@HOST"
[[ -f $key && -r $key ]] || die "--key must name a readable key file"
[[ -f $exe && -x $exe ]] || die "no executable at $exe; build it from the tested commit first"
git -C "$ROOT" diff --quiet HEAD -- || die "the checkout has uncommitted changes; the tested commit must be HEAD"
commit=$(git -C "$ROOT" rev-parse HEAD)

SSH=(ssh -i "$key" -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=30)
STAGE=
REMOTE=
RUN_PID=

# Runs bash on the machine with a script on standard input: remote ARGS... <script.
remote() {
    "${SSH[@]}" "$vm" bash -s -- "$@"
}

# Waits for the story's run to let go of its lock, removes the run directory, and names anything left.
# shellcheck disable=SC2329 # called by the EXIT trap
finish() {
    local status=$? left
    trap '' INT TERM HUP
    if [[ -n $RUN_PID ]] && kill -0 "$RUN_PID" 2>/dev/null; then
        kill -TERM "$RUN_PID" 2>/dev/null || true
        wait "$RUN_PID" 2>/dev/null || true
    fi
    [[ -n $STAGE ]] && rm -rf -- "$STAGE"
    if [[ -n $REMOTE ]]; then
        # The story's clean-up holds the lock until it has undone what it did; flock waits for it.
        remote "$REMOTE" <<'EOF' || status=3
set -u
dir=$1
case $dir in "${TMPDIR:-/tmp}"/porelo-story-test.*) ;; *) echo "not a run directory: $dir" >&2; exit 3 ;; esac
if [ -d "$dir" ]; then
    flock -w 120 "$dir/run.lock" true || echo "the story's run still holds $dir/run.lock" >&2
    rm -rf -- "$dir"
fi
[ ! -e "$dir" ] || { echo "left behind: $dir" >&2; exit 3; }
EOF
        if left=$(remote leftovers <"$HERE/story-test.sh"); then
            printf '%s: the machine is clean: no run directory, no key or host line of a run, no planner\n' "$NAME"
        else
            printf '%s: left behind on the machine:\n%s\n' "$NAME" "$left" >&2
            status=3
        fi
    fi
    exit "$status"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

printf '%s: %s at %s on %s\n' "$NAME" "$story" "$commit" "${vm#*@}"
printf '%s: executable sha256 %s\n' "$NAME" "$(sha256sum "$exe" | cut -d' ' -f1)"

left=$(remote leftovers <"$HERE/story-test.sh") || die "the machine holds an earlier run's leftovers:
$left"

STAGE=$(mktemp -d)
git -C "$ROOT" bundle create -q "$STAGE/source.bundle" HEAD
cp -- "$exe" "$STAGE/porelo-planner"

# shellcheck disable=SC2016 # expanded by the machine's shell
REMOTE=$("${SSH[@]}" "$vm" 'umask 077; mktemp -d "${TMPDIR:-/tmp}/porelo-story-test.XXXXXXXX"') ||
    die "no run directory could be made on the machine"
[[ $REMOTE =~ ^/[A-Za-z0-9_][A-Za-z0-9._-]*(/[A-Za-z0-9_][A-Za-z0-9._-]*)*/porelo-story-test\.[A-Za-z0-9]{8}$ ]] ||
    {
        printf '%s: the machine answered no run directory: %q\n' "$NAME" "$REMOTE" >&2
        REMOTE=
        exit 2
    }

tar -C "$STAGE" -cf - source.bundle porelo-planner | "${SSH[@]}" "$vm" tar -xf - -C "$REMOTE" ||
    die "the bundle and the executable did not reach the machine"
remote "$REMOTE" "$commit" <<'EOF' || die "the tested commit could not be checked out on the machine"
set -eu
cd "$1"
git init -q source
git -C source fetch -q ../source.bundle HEAD
git -C source -c advice.detachedHead=false checkout -q FETCH_HEAD
[ "$(git -C source rev-parse HEAD)" = "$2" ]
rm -f source.bundle
chmod 700 porelo-planner
EOF

mkdir -p "$ROOT/target/story-tests"
report=$ROOT/target/story-tests/$story.log
# A terminal on the machine, so an interrupt here reaches the story's script there as one, and a lost connection
# as a hang-up; either way it cleans up behind it.
"${SSH[@]}" -tt "$vm" "cd $REMOTE && exec flock -n run.lock bash source/tool/story-tests/$story.sh --dir $REMOTE ${expects[*]}" \
    </dev/null > >(tr -d '\r' | tee "$report") 2>&1 &
RUN_PID=$!
code=0
wait "$RUN_PID" || code=$?
RUN_PID=
printf '%s: %s ended with exit code %s; report in %s\n' "$NAME" "$story" "$code" "$report"
exit "$code"
