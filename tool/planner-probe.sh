#!/usr/bin/env bash
# Reads what the planner, its Git store and the forge show after one step of a real run, and checks
# it against what the step must have made. It reads only: no call that saves, moves or starts, and a
# clone it needs lives in a temporary directory it removes.
#
# Usage: planner-probe.sh <check> [arguments]
#
#   health                               the planner answers UP
#   faults                               the board was read without a fault
#   story-id <title>                     prints the id of the one story with this title
#   story-state <story> <state>          the board shows the story in this state
#   issue-state <issue> <state>          the board shows the issue in this state, needing nobody
#   issues <story>                       prints each issue of the story with its state
#   work <story> none|offered|started|ended
#                                        the story's run: no record, offers not yet started, a task
#                                        at work, or every started task ended and none at work
#   attention <issue> <reason>           the issue's attention reason, NONE when nobody must act
#   events <seconds>                     the changes stream sends a board within this time
#   store-issue <clone-url> <issue> <state>
#                                        the issue's file on the branch's tip says this state, its
#                                        stage records end in it and the commit that set it starts no build;
#                                        a closed issue has no file, only its unlinked index row
#   store-story <clone-url> <story> <issue> <state>
#                                        the story's file lists the issue in this state
#   forge-head <clone-url>               prints the branch's tip as the forge has it
#   forge-moved <clone-url> <commit>     the branch's tip is no longer this commit; prints the commits since
#   records <since>                      Porelo's task records of the project since this UTC time
#
# Environment:
#   PLANNER_PROJECT       the project's name (required)
#   PLANNER_URL           the planner's address; read from the address file when unset
#   PLANNER_ADDRESS_FILE  the address file; $XDG_RUNTIME_DIR/porelo-planner/planner.json when unset
#   PLANNER_TOKEN         the bearer token; read from the address file when unset
#   PLANNER_BRANCH        the store's branch; main when unset
#   PORELO                Porelo's executable for 'records'; porelo on the PATH when unset
#   PORELO_PROJECT        Porelo's project for 'records'; PLANNER_PROJECT when unset
#
# Exit codes: 0 the check holds, 1 it does not, 2 the probe cannot run (usage, a tool missing, the
# planner or the forge not reachable). Every failure prints what was asked and what was got.

set -euo pipefail

readonly PROBE=${0##*/}

die() {
    printf '%s: %s\n' "$PROBE" "$1" >&2
    exit 2
}

fail() {
    printf '%s: FAIL %s\n  asked: %s\n  got:   %s\n' "$PROBE" "$1" "$2" "$3" >&2
    exit 1
}

pass() {
    printf '%s: OK %s\n' "$PROBE" "$1"
}

need() {
    command -v "$1" >/dev/null 2>&1 || die "$1 is not installed"
}

need curl
need jq

PROJECT=${PLANNER_PROJECT:-}
[[ $PROJECT =~ ^[a-z0-9][a-z0-9._-]{0,99}$ ]] || die "PLANNER_PROJECT is unset or not a project name"
PORELO_PROJECT=${PORELO_PROJECT:-$PROJECT}
[[ $PORELO_PROJECT =~ ^[a-z0-9][a-z0-9._-]{0,99}$ ]] || die "PORELO_PROJECT is not a project name"
BRANCH=${PLANNER_BRANCH:-main}
[[ $BRANCH =~ ^[A-Za-z0-9][A-Za-z0-9._/-]{0,199}$ ]] || die "PLANNER_BRANCH is not a branch name"

address_file() {
    local file=${PLANNER_ADDRESS_FILE:-${XDG_RUNTIME_DIR:-}/porelo-planner/planner.json}
    [[ -f $file && -r $file ]] || die "no readable address file at $file; set PLANNER_URL and PLANNER_TOKEN"
    printf '%s' "$file"
}

base_url() {
    if [[ -n ${PLANNER_URL:-} ]]; then
        [[ $PLANNER_URL =~ ^http://(127\.0\.0\.1|localhost|\[::1\]):[0-9]{1,5}$ ]] ||
            die "PLANNER_URL must be http://127.0.0.1:<port>"
        printf '%s' "$PLANNER_URL"
        return
    fi
    local port
    port=$(jq -er '.port' "$(address_file)") || die "the address file names no port"
    [[ $port =~ ^[0-9]{1,5}$ ]] || die "the address file's port is not a number"
    printf 'http://127.0.0.1:%s' "$port"
}

read_token() {
    local token=${PLANNER_TOKEN:-}
    if [[ -z $token ]]; then
        token=$(jq -er '.token' "$(address_file)") || die "the address file names no token"
    fi
    [[ $token =~ ^[[:graph:]]+$ && $token != *'"'* && $token != *\\* ]] ||
        die "the token holds a character a header cannot carry"
    printf '%s' "$token"
}

URL=$(base_url)
readonly URL
# Read and checked once here, outside any pipeline, so a bad token stops the probe before any call.
TOKEN=$(read_token)
readonly TOKEN

# The token reaches curl on its standard input as a config line, never on a command line.
auth_config() {
    printf 'header = "Authorization: Bearer %s"\n' "$TOKEN"
}

# GET one path of the project's API; prints the body, exits 2 with the status on anything but 200.
get() {
    local path=$1 body status
    body=$(mktemp)
    status=$(auth_config | curl -sS -K - -o "$body" -w '%{http_code}' --max-time 30 \
        -H 'Accept: application/json' "$URL$path") || {
        rm -f "$body"
        die "GET $path did not answer"
    }
    if [[ $status != 200 ]]; then
        local detail
        detail=$(jq -r '.type + ": " + .detail' "$body" 2>/dev/null || true)
        rm -f "$body"
        die "GET $path answered $status ${detail}"
    fi
    cat "$body"
    rm -f "$body"
}

api() {
    get "/projects/$PROJECT$1"
}

story_arg() {
    [[ $1 =~ ^ST[0-9]{1,5}$ ]] || die "$1 is not a story id"
}

issue_arg() {
    [[ $1 =~ ^[A-Z]{1,4}[0-9]{1,5}(\.[0-9]{1,3})?$ ]] || die "$1 is not an issue id"
}

url_arg() {
    [[ $1 =~ ^(https://|ssh://|[A-Za-z0-9._-]+@)[^[:space:]]+$ ]] || die "$1 is not a clone URL"
}

CLONE=""
cleanup() {
    if [[ -n $CLONE && -d $CLONE ]]; then
        rm -rf -- "$CLONE"
    fi
}
trap cleanup EXIT INT TERM

# A bare, blob-less clone of the branch in a temporary directory, with the account's own Git login.
clone() {
    local url=$1
    need git
    CLONE=$(mktemp -d)
    GIT_TERMINAL_PROMPT=0 git clone -q --bare --filter=blob:none --single-branch --branch "$BRANCH" \
        -- "$url" "$CLONE/repo.git" || die "cannot clone $url at $BRANCH"
}

show() {
    git --git-dir "$CLONE/repo.git" show "$BRANCH:$1"
}

file_of() {
    local dir=$1 id=$2 name
    name=$(git --git-dir "$CLONE/repo.git" ls-tree --name-only "$BRANCH" -- "$dir/" |
        grep -E "^$dir/${id}(-[^/]*)?\.md$" || true)
    [[ $(printf '%s\n' "$name" | grep -c . || true) == 1 ]] || fail "$id's file" "one file $dir/$id-*.md" "${name:-none}"
    printf '%s' "$name"
}

# Whether the issue has no file and its index row stands unlinked, as the store settles a closed one.
settled_row() {
    local id=$1
    [[ -z $(git --git-dir "$CLONE/repo.git" ls-tree --name-only "$BRANCH" -- issues/ |
        grep -E "^issues/${id}(-[^/]*)?\.md$" || true) ]] || return 1
    show issues/README.md | grep -qF "<a id=\"${id,,}\"></a>${id} " || return 1
}

check=${1:-}
[[ -n $check ]] || die "usage: $PROBE <check> [arguments]; see the head of the script"
shift

case $check in
health)
    got=$(get /health | jq -r '.status')
    [[ $got == UP ]] || fail "health" "UP" "$got"
    pass "the planner at $URL answers UP"
    ;;
faults)
    got=$(api /workflow | jq -c '.faults')
    [[ $got == '[]' ]] || fail "faults" "[]" "$got"
    pass "the board of $PROJECT was read without a fault"
    ;;
story-id)
    title=${1:?story-id needs a title}
    ids=$(api /stories | jq -r --arg t "$title" '[.stories[] | select(.title == $t) | .id] | join(" ")')
    [[ $ids =~ ^ST[0-9]+$ ]] || fail "story-id" "one story titled '$title'" "${ids:-none}"
    printf '%s\n' "$ids"
    ;;
story-state)
    story_arg "${1:-}"
    want=${2:?story-state needs a state}
    got=$(api "/stories/$1" | jq -r '.story.state')
    [[ $got == "$want" ]] || fail "story-state $1" "$want" "$got"
    pass "$1 stands in $want"
    ;;
issue-state)
    issue_arg "${1:-}"
    want=${2:?issue-state needs a state}
    reply=$(api "/issues/$1")
    got=$(jq -r '.issue.state' <<<"$reply")
    [[ $got == "$want" ]] || fail "issue-state $1" "$want" "$got"
    raised=$(jq -r '(.issue.needsYou | tostring) + " " + .issue.attention' <<<"$reply")
    [[ $raised == "false NONE" ]] || fail "issue-state $1" "needsYou false, attention NONE" "$raised"
    pass "$1 stands in $want and needs nobody"
    ;;
issues)
    story_arg "${1:-}"
    api "/stories/$1/issues" | jq -r '.issues[] | "\(.id) \(.repository) \(.state) worker=\(.worker) atWork=\(.atWork) attention=\(.attention)"'
    ;;
work)
    story_arg "${1:-}"
    want=${2:?work needs none, offered, started or ended}
    reply=$(api "/stories/$1/work")
    got=$(jq -r '
        if .work == null then "none"
        elif (.work.tasks | length) == 0 then "offered"
        elif (.work.tasks | any(.atWork)) then "started"
        elif (.work.tasks | all(.ended != null)) then "ended"
        else "unclear" end' <<<"$reply")
    jq -r '.work.tasks[]? | "  task \(.task) \(.role) \(.repository) \(.state)->\(.done) \(.agent)/\(.model) atWork=\(.atWork) finished=\(.ended.finished // "-") unpushed=\(.ended.unpushed // "-")"' <<<"$reply"
    [[ $got == "$want" ]] || fail "work $1" "$want" "$got"
    pass "$1's run is $want"
    ;;
attention)
    issue_arg "${1:-}"
    want=${2:?attention needs a reason}
    got=$(api "/issues/$1" | jq -r '.issue.attention')
    [[ $got == "$want" ]] || fail "attention $1" "$want" "$got"
    pass "$1's attention is $want"
    ;;
events)
    seconds=${1:?events needs a number of seconds}
    [[ $seconds =~ ^[0-9]{1,3}$ ]] || die "events needs a number of seconds"
    stream=$(mktemp)
    code=0
    auth_config | curl -sS -f -N -K - --max-time "$seconds" -H 'Accept: text/event-stream' \
        -o "$stream" "$URL/projects/$PROJECT/events" 2>/dev/null || code=$?
    # 28 is curl's own time limit, which ends every stream that is still open.
    if [[ $code != 0 && $code != 28 ]]; then
        rm -f "$stream"
        die "the changes stream of $PROJECT did not open (curl exit $code)"
    fi
    got=$(grep -c -E '^event: ?board$' "$stream" || true)
    rm -f "$stream"
    [[ $got -ge 1 ]] || fail "events" "one board event within ${seconds}s" "none"
    pass "the changes stream of $PROJECT sends a board"
    ;;
store-issue)
    url_arg "${1:-}"
    issue_arg "${2:-}"
    want=${3:?store-issue needs a state}
    clone "$1"
    if settled_row "$2"; then
        # A closed issue's file goes; its index row, unlinked, is all that is left of it.
        file=issues/README.md
        printf '%s: %s is settled: no file, its row in %s stands without a link\n' "$PROBE" "$2" "$file"
    else
        file=$(file_of issues "$2")
        got=$(show "$file" | awk '/^```json$/{on=1;next} /^```$/{if(on)exit} on' | jq -r '.state')
        [[ $got == "$want" ]] || fail "store-issue $2" "$file says $want" "$got"
    fi
    record=$(show "stats/$2.jsonl" 2>/dev/null | tail -n 1 | jq -r '.status_after' || true)
    # A new issue gets its stage records with its first move; until then it has none.
    if [[ -z $record && $want == Idea ]]; then
        printf '%s: stats/%s.jsonl not there yet, as for an issue never moved\n' "$PROBE" "$2"
    else
        [[ $record == "$want" ]] || fail "store-issue $2" "stats/$2.jsonl ends in $want" "${record:-no record}"
    fi
    subject=$(git --git-dir "$CLONE/repo.git" log -1 --format=%s "$BRANCH" -- "$file")
    [[ $subject == *'[skip ci]' ]] || fail "store-issue $2" "the commit that set it ends with [skip ci]" "$subject"
    pass "$file says $want, its stage records end in it, set by '$subject'"
    ;;
store-story)
    url_arg "${1:-}"
    story_arg "${2:-}"
    issue_arg "${3:-}"
    want=${4:?store-story needs a state}
    clone "$1"
    file=$(file_of stories "$2")
    line=$(show "$file" | grep -E "#$3\]\(" || true)
    [[ $line == *"($want)" ]] || fail "store-story $2" "$file lists $3 ($want)" "${line:-no line for $3}"
    show stories/README.md | grep -q -E "\b$2\b" || fail "store-story $2" "stories/README.md lists $2" "no row"
    pass "$file lists $3 in $want, and the stories index names $2"
    ;;
forge-head)
    url_arg "${1:-}"
    need git
    head=$(GIT_TERMINAL_PROMPT=0 git ls-remote -- "$1" "refs/heads/$BRANCH" | cut -f1)
    [[ $head =~ ^[0-9a-f]{40,64}$ ]] || die "the forge answered no tip for $BRANCH of $1"
    printf '%s\n' "$head"
    ;;
forge-moved)
    url_arg "${1:-}"
    old=${2:?forge-moved needs a commit}
    [[ $old =~ ^[0-9a-f]{40,64}$ ]] || die "$old is not a full commit id"
    clone "$1"
    new=$(git --git-dir "$CLONE/repo.git" rev-parse "$BRANCH")
    [[ $new != "$old" ]] || fail "forge-moved" "a tip other than $old" "$new"
    git --git-dir "$CLONE/repo.git" log --format='  %h %an: %s' "$old..$BRANCH" 2>/dev/null ||
        fail "forge-moved" "$old an ancestor of the tip" "not found in the branch's history"
    pass "$BRANCH moved from $old to $new"
    ;;
records)
    since=${1:?records needs a UTC time}
    [[ $since =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || die "records needs a time like 2026-01-01T00:00:00Z"
    porelo=${PORELO:-porelo}
    need "$porelo"
    got=$("$porelo" task records -p "$PORELO_PROJECT" --since "$since" --json) || die "Porelo did not answer its task records"
    count=$(jq -r 'if type == "array" then length else (.records // [] | length) end' <<<"$got")
    [[ $count =~ ^[1-9][0-9]*$ ]] || fail "records" "at least one task record since $since" "${count:-none}"
    pass "Porelo holds $count task record(s) of $PROJECT since $since"
    ;;
*)
    die "unknown check $check; see the head of the script"
    ;;
esac
