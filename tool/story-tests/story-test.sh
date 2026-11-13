#!/usr/bin/env bash
# The shared ground of this repository's story tests, sourced by each story's script on the test machine: a forge
# of bare repositories reached over SSH on localhost with a key authorized for this run alone, the planner started
# as the window starts it, one report line per step, and a clean-up that undoes everything the run did, on an
# interrupt too, and names anything it left.
#
# A story script sets ST_STORY and ST_LIBRARY=1, sources this file, calls st_setup "$@", builds its fixture with
# st_repo, st_write, st_copy and st_push, starts the planner with st_planner, runs each step with st_step and ends
# with st_finish. Run directly, 'story-test.sh leftovers' names what an earlier run left on this account and exits 1
# if anything.
#
# Arguments every story script takes:
#   --dir DIR          the run's own directory, holding only source/ and porelo-planner; the run works there
#   --expect N=ISSUE   step N fails until ISSUE is built; repeatable; N 'story' names an issue no step plays
#
# The run's directory holds source/, a checkout of the tested commit, and porelo-planner, the native executable built
# from it. The run adds a line to ~/.ssh/authorized_keys and one to ~/.ssh/known_hosts, each ending in its own mark,
# and starts the planner in the account's runtime directory; all of it goes again at the end, and so does everything
# the run made in its directory.
#
# Exit codes: 0 every step passed or failed as expected, 1 a step failed, 2 the run could not be made, 3 the
# clean-up left something, which it names.

set -euo pipefail

readonly ST_MARK_PREFIX=porelo-story-test
ST_NAME=${0##*/}
ST_STORY=${ST_STORY:-}
ST_DIR=
ST_EXE=
ST_SRC=
ST_MARK=
ST_RUNTIME=
ST_PID=
ST_AGENT_PID=
ST_KEY_LINE=
ST_HOST_LINE=
ST_MADE_SSH_DIR=0
ST_MADE_KEYS=0
ST_MADE_HOSTS=0
ST_MADE_PLANNER_DIR=0
ST_FAILED=0
ST_READY=0
ST_URL=
ST_TOKEN=
ST_STATUS=
ST_BODY=
ST_ASKED=
ST_GOT=
declare -A ST_EXPECT=()

st_say() {
    printf '%s: %s\n' "$ST_NAME" "$1" >&2
}

st_die() {
    st_say "$1"
    exit 2
}

st_need() {
    local tool
    for tool in "$@"; do
        command -v "$tool" >/dev/null 2>&1 || st_die "$tool is not installed"
    done
}

st_account() {
    id -un
}

st_runtime() {
    printf '%s' "${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
}

# What an earlier run left on this account; prints one line per thing, nothing when the account is clean.
st_leftovers() {
    local file dir
    for file in "$HOME/.ssh/authorized_keys" "$HOME/.ssh/known_hosts"; do
        if [[ -f $file ]] && grep -q -F "$ST_MARK_PREFIX" "$file"; then
            printf 'a line marked %s in %s\n' "$ST_MARK_PREFIX" "$file"
        fi
    done
    if [[ -e $(st_runtime)/porelo-planner/planner.json ]]; then
        printf 'a planner of this account: %s\n' "$(st_runtime)/porelo-planner/planner.json"
    fi
    for dir in "${TMPDIR:-/tmp}"/porelo-story-test.*; do
        if [[ -e $dir && $dir != "$ST_DIR" ]]; then
            printf 'a run directory: %s\n' "$dir"
        fi
    done
    return 0
}

# Reads the arguments every story script takes, refuses a machine with leftovers and arms the clean-up.
st_setup() {
    local step
    while (($#)); do
        case $1 in
        --dir)
            ST_DIR=${2:?--dir needs a directory}
            shift 2
            ;;
        --expect)
            [[ ${2:-} =~ ^([0-9]+|story)=([A-Z]{1,4}[0-9]{1,5})$ ]] || st_die "--expect takes N=ISSUE, got '${2:-}'"
            step=${BASH_REMATCH[1]}
            ST_EXPECT[$step]=${ST_EXPECT[$step]:+${ST_EXPECT[$step]}, }${BASH_REMATCH[2]}
            shift 2
            ;;
        *) st_die "unknown argument '$1'" ;;
        esac
    done
    [[ $ST_STORY =~ ^ST[0-9]+$ ]] || st_die "the story script set no ST_STORY"
    st_need git curl jq ssh ssh-keygen ssh-agent ssh-add setsid find awk
    # A clone URL takes the directory as its path, so it holds only what Git and the planner take there.
    [[ $ST_DIR =~ ^/[A-Za-z0-9_][A-Za-z0-9._-]*(/[A-Za-z0-9_][A-Za-z0-9._-]*)*$ ]] ||
        st_die "--dir must be a plain absolute path, got '$ST_DIR'"
    [[ -d $ST_DIR && -O $ST_DIR ]] || st_die "--dir must name a directory of this account"
    ST_SRC=$ST_DIR/source
    ST_EXE=$ST_DIR/porelo-planner
    [[ -d $ST_SRC/.git ]] || st_die "$ST_SRC is no checkout"
    [[ -f $ST_EXE && -x $ST_EXE ]] || st_die "$ST_EXE is no executable"
    local extra
    extra=$(find "$ST_DIR" -mindepth 1 -maxdepth 1 ! -name source ! -name porelo-planner ! -name run.lock -printf '%f ')
    [[ -z $extra ]] || st_die "$ST_DIR holds more than source/ and porelo-planner: $extra"
    ST_RUNTIME=$(st_runtime)
    [[ -d $ST_RUNTIME && -O $ST_RUNTIME ]] || st_die "the account's runtime directory $ST_RUNTIME is missing"
    [[ -r /etc/ssh/ssh_host_ed25519_key.pub ]] || st_die "localhost has no ed25519 host key to trust"
    local left
    left=$(st_leftovers)
    [[ -z $left ]] || st_die "an earlier run left behind:
$left"
    local base=${ST_DIR##*/}
    ST_MARK=$ST_MARK_PREFIX-${base##*.}
    trap st_cleanup EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    trap 'exit 129' HUP
    mkdir -m 700 "$ST_DIR/forge" "$ST_DIR/work" "$ST_DIR/clones" "$ST_DIR/xdg"
    mkdir -m 700 "$ST_DIR/xdg/cache" "$ST_DIR/xdg/state"
    st_forge
    ST_READY=1
}

# The forge: a key of its own, held by an agent of its own, authorized for localhost alone, and localhost's host key
# known, since the planner's Git calls take the agent but set their own ssh command, which reads only the account's
# own known hosts.
st_forge() {
    ssh-keygen -q -t ed25519 -N '' -C "$ST_MARK" -f "$ST_DIR/key" >/dev/null || st_die "no key could be made"
    if [[ ! -d $HOME/.ssh ]]; then
        mkdir -m 700 "$HOME/.ssh"
        ST_MADE_SSH_DIR=1
    fi
    [[ -e $HOME/.ssh/authorized_keys ]] || ST_MADE_KEYS=1
    [[ -e $HOME/.ssh/known_hosts ]] || ST_MADE_HOSTS=1
    local key host
    read -r key <"$ST_DIR/key.pub"
    ST_KEY_LINE="restrict,from=\"127.0.0.1,::1\" ${key% *} $ST_MARK"
    printf '%s\n' "$ST_KEY_LINE" >>"$HOME/.ssh/authorized_keys"
    chmod 600 "$HOME/.ssh/authorized_keys"
    read -r host <"/etc/ssh/ssh_host_ed25519_key.pub"
    host=${host#* }
    ST_HOST_LINE="localhost ssh-ed25519 ${host%% *} $ST_MARK"
    printf '%s\n' "$ST_HOST_LINE" >>"$HOME/.ssh/known_hosts"
    local agent
    agent=$(ssh-agent -s -a "$ST_DIR/agent.sock") || st_die "no ssh-agent could be started"
    [[ $agent =~ SSH_AGENT_PID=([0-9]+) ]] || st_die "ssh-agent named no process"
    ST_AGENT_PID=${BASH_REMATCH[1]}
    export SSH_AUTH_SOCK=$ST_DIR/agent.sock
    ssh-add -q "$ST_DIR/key" 2>/dev/null || st_die "the run's key could not be added to its agent"
    export GIT_CONFIG_GLOBAL=$ST_DIR/gitconfig
    export GIT_CONFIG_NOSYSTEM=1
    export GIT_SSH_COMMAND='ssh -o BatchMode=yes'
    export GIT_TERMINAL_PROMPT=0
    git config --file "$GIT_CONFIG_GLOBAL" user.name 'Story Test'
    git config --file "$GIT_CONFIG_GLOBAL" user.email 'story-test@example.org'
    git config --file "$GIT_CONFIG_GLOBAL" init.defaultBranch main
    ssh -o BatchMode=yes "$(st_account)@localhost" true </dev/null ||
        st_die "the run's key does not reach $(st_account)@localhost; does sshd take keys there?"
}

# The clone URL of a forge repository, as the planner takes it.
st_url() {
    printf 'ssh://%s@localhost%s/forge/%s.git' "$(st_account)" "$ST_DIR" "$1"
}

# Makes a bare repository on the forge and a working tree for its first commit in work/NAME.
st_repo() {
    git init -q --bare -b main "$ST_DIR/forge/$1.git"
    git init -q -b main "$ST_DIR/work/$1"
    git -C "$ST_DIR/work/$1" remote add origin "$(st_url "$1")"
}

# Writes standard input to a file of a working tree: st_write NAME PATH.
st_write() {
    mkdir -p "$(dirname "$ST_DIR/work/$1/$2")"
    cat >"$ST_DIR/work/$1/$2"
}

# Copies a file of the tested commit into a working tree: st_copy NAME PATH SOURCE-PATH.
st_copy() {
    mkdir -p "$(dirname "$ST_DIR/work/$1/$2")"
    cp -- "$ST_SRC/$3" "$ST_DIR/work/$1/$2"
}

# Runs the planner's own command line in a directory: st_cli DIR ARGS...; its output lands in ST_BODY.
st_cli() {
    local dir=$1 code=0
    shift
    ST_BODY=$(cd "$dir" && "$ST_EXE" "$@" 2>&1) || code=$?
    return "$code"
}

# Commits everything in work/NAME and pushes it.
st_push() {
    git -C "$ST_DIR/work/$1" add -A
    git -C "$ST_DIR/work/$1" commit -q -m "$2"
    git -C "$ST_DIR/work/$1" push -q origin main
}

# Waits up to 60 s until the planner's clones hold no save it has not pushed yet, else names them and answers 1. The
# planner answers a save once its commits stand in its own clones and pushes them in the background, the project's own
# repository last, and fetches no repository over a save not pushed yet.
st_pushed() {
    local tenths=600 clone waiting clones=()
    [[ -n $ST_PID ]] || return 0
    while IFS= read -r -d '' clone; do
        clones+=("$clone")
    done < <(find "$ST_DIR/xdg/cache" -mindepth 1 -maxdepth 3 -type d -name '*.git' -print0)
    if ((!${#clones[@]})); then
        st_say "no clone of the planner's in $ST_DIR/xdg/cache"
        return 1
    fi
    while :; do
        waiting=
        for clone in "${clones[@]}"; do
            if [[ -n $(git -C "$clone" for-each-ref refs/planner/local) ]]; then waiting+=" ${clone##*/}"; fi
        done
        [[ -n $waiting ]] || return 0
        if ((tenths-- <= 0)); then
            st_say "the planner's saves are not pushed after 60 s:$waiting"
            return 1
        fi
        sleep 0.1
    done
}

# Fresh clones of forge repositories side by side, as the planner's own commands expect them, once the planner's saves
# are pushed; prints the directory holding them: st_clones NAME...
st_clones() {
    local into name
    st_pushed || return 1
    into=$(mktemp -d "$ST_DIR/clones/c.XXXXXX")
    for name in "$@"; do
        git clone -q "$(st_url "$name")" "$into/$name" || return 1
    done
    printf '%s' "$into"
}

# Starts the planner as the window does: the executable with no arguments, detached, in the account's runtime
# directory; its clones and log go to the run's directory. Then names the project with its own repository.
st_planner() {
    local project=$1 repository=$2 dir file waited=0
    dir=$ST_RUNTIME/porelo-planner
    file=$dir/planner.json
    [[ -d $dir ]] || ST_MADE_PLANNER_DIR=1
    # Without job control a background job leads no process group, so setsid execs into the planner itself.
    (cd "$ST_RUNTIME" && exec env XDG_RUNTIME_DIR="$ST_RUNTIME" XDG_CACHE_HOME="$ST_DIR/xdg/cache" \
        XDG_STATE_HOME="$ST_DIR/xdg/state" setsid "$ST_EXE" >"$ST_DIR/planner.out" 2>&1 </dev/null) &
    ST_PID=$!
    until [[ -s $file ]] && jq -e '.port and .token' "$file" >/dev/null 2>&1; do
        kill -0 "$ST_PID" 2>/dev/null || st_die "the planner ended at its start; its output:
$(grep -v -F "$file" "$ST_DIR/planner.out" | tail -n 20)"
        ((waited++ < 600)) || st_die "the planner wrote no address file within 60 s"
        sleep 0.1
    done
    local port
    port=$(jq -r '.port' "$file")
    [[ $port =~ ^[0-9]{1,5}$ ]] || st_die "the address file's port is no number"
    ST_URL=http://127.0.0.1:$port
    ST_TOKEN=$(jq -r '.token' "$file")
    [[ $ST_TOKEN =~ ^[[:graph:]]+$ && $ST_TOKEN != *'"'* && $ST_TOKEN != *\\* ]] ||
        st_die "the address file's token holds a character a header cannot carry"
    st_api GET /health
    [[ $ST_STATUS == 200 ]] || st_die "the planner's health answers $ST_STATUS: $ST_BODY"
    st_api PUT "/projects/$project" "$(jq -nc --arg r "$(st_url "$repository")" '{repository: $r, branch: "main"}')"
    [[ $ST_STATUS == 204 ]] || st_die "PUT /projects/$project answered $ST_STATUS: $ST_BODY"
}

# One call to the planner, as the window makes it; sets ST_STATUS and ST_BODY.
st_api() {
    local method=$1 path=$2 body=${3:-} out=$ST_DIR/answer.json
    local args=(-sS --max-time 120 -K - -o "$out" -w '%{http_code}' -X "$method" -H 'Accept: application/json')
    if [[ -n $body ]]; then
        args+=(-H 'Content-Type: application/json' --data-binary "$body")
    fi
    rm -f "$out"
    # The token reaches curl on its standard input, never on a command line.
    ST_STATUS=$(printf 'header = "Authorization: Bearer %s"\n' "$ST_TOKEN" | curl "${args[@]}" "$ST_URL$path") ||
        ST_STATUS=000
    ST_BODY=$(cat "$out" 2>/dev/null || true)
}

# Fails the step under way with what was asked and what was got.
st_no() {
    ST_ASKED=$1
    ST_GOT=$2
    return 1
}

# Holds when two values agree, else fails the step: st_is WHAT WANT GOT.
st_is() {
    [[ $2 == "$3" ]] || st_no "$1: $2" "$3"
}

# The JSON head of a story or issue file, the block between ```json and ```.
st_head() {
    awk '/^```json$/{on=1;next} /^```$/{if(on)exit} on' "$1"
}

# The heading a row stands under in a Markdown index: st_section FILE ANCHOR.
st_section() {
    awk -v anchor="<a id=\"$2\"></a>" '/^## /{section=substr($0,4)} index($0,anchor){print section; exit}' "$1"
}

# Runs one step: a function that answers 0 when the step holds, and on failure calls st_no.
st_step() {
    local number=$1 what=$2 expected
    shift 2
    expected=${ST_EXPECT[$number]:-}
    ST_ASKED=
    ST_GOT=
    if ((!ST_READY)); then
        printf 'STEP %s %s: SKIPPED, the run could not be made\n' "$number" "$what"
    elif "$@"; then
        printf 'STEP %s %s: PASS%s\n' "$number" "$what" "${expected:+ (expected to fail until $expected is built)}"
    elif [[ -n $expected ]]; then
        printf 'STEP %s %s: EXPECTED FAIL, %s not built\n  asked: %s\n  got:   %s\n' "$number" "$what" "$expected" \
            "$ST_ASKED" "$ST_GOT"
    else
        printf 'STEP %s %s: FAIL\n  asked: %s\n  got:   %s\n' "$number" "$what" "$ST_ASKED" "$ST_GOT"
        ST_FAILED=1
    fi
}

st_finish() {
    if [[ -n ${ST_EXPECT[story]:-} ]]; then
        printf 'STORY %s: EXPECTED FAIL, %s not built; no step plays it\n' "$ST_STORY" "${ST_EXPECT[story]}"
    fi
    if ((ST_FAILED)); then
        st_say "a step failed; the planner's last warnings and errors:"
        grep -E '(WARN|ERROR)' "$ST_DIR/planner.out" 2>/dev/null | grep -v -F -e "$ST_TOKEN" | tail -n 20 >&2 || true
        exit 1
    fi
    printf 'STORY %s: every step passed or failed as expected\n' "$ST_STORY"
    exit 0
}

# Removes exactly one line from a file, keeping the file and its mode.
st_drop_line() {
    local file=$1 line=$2 kept
    [[ -n $line && -f $file ]] || return 0
    kept=$(grep -v -x -F -- "$line" "$file" || true)
    if [[ -n $kept ]]; then printf '%s\n' "$kept" >"$file"; else : >"$file"; fi
}

# Waits up to a number of tenths of a second for a process to end; answers 0 once it has.
st_ended() {
    local pid=$1 tenths=$2
    while ((tenths-- > 0)); do
        kill -0 "$pid" 2>/dev/null || return 0
        sleep 0.1
    done
    ! kill -0 "$pid" 2>/dev/null
}

st_cleanup() {
    local status=$? left=()
    trap '' INT TERM HUP PIPE
    if [[ -n $ST_PID ]] && kill -0 "$ST_PID" 2>/dev/null; then
        kill -TERM "$ST_PID" 2>/dev/null || true
        if ! st_ended "$ST_PID" 150; then
            st_say "the planner did not stop within 15 s of TERM; killing it"
            kill -KILL "$ST_PID" 2>/dev/null || true
            st_ended "$ST_PID" 50 || true
        fi
    fi
    if [[ -n $ST_PID ]]; then
        if kill -0 "$ST_PID" 2>/dev/null; then
            left+=("the planner, process $ST_PID")
        else
            # A planner killed hard leaves its address file; a lock directory the run made goes with it.
            rm -f "$ST_RUNTIME/porelo-planner/planner.json"
            if ((ST_MADE_PLANNER_DIR)); then rm -rf "$ST_RUNTIME/porelo-planner"; fi
        fi
    fi
    if [[ -n $ST_AGENT_PID ]]; then
        kill "$ST_AGENT_PID" 2>/dev/null || true
        st_ended "$ST_AGENT_PID" 50 || left+=("the ssh-agent, process $ST_AGENT_PID")
    fi
    st_drop_line "$HOME/.ssh/authorized_keys" "$ST_KEY_LINE"
    st_drop_line "$HOME/.ssh/known_hosts" "$ST_HOST_LINE"
    if ((ST_MADE_KEYS)) && [[ ! -s $HOME/.ssh/authorized_keys ]]; then rm -f "$HOME/.ssh/authorized_keys"; fi
    if ((ST_MADE_HOSTS)) && [[ ! -s $HOME/.ssh/known_hosts ]]; then rm -f "$HOME/.ssh/known_hosts"; fi
    if ((ST_MADE_SSH_DIR)); then rmdir "$HOME/.ssh" 2>/dev/null || true; fi
    if [[ -n $ST_MARK ]]; then
        if grep -q -F "$ST_MARK" "$HOME/.ssh/authorized_keys" 2>/dev/null; then
            left+=("its key in ~/.ssh/authorized_keys")
        fi
        if grep -q -F "$ST_MARK" "$HOME/.ssh/known_hosts" 2>/dev/null; then
            left+=("its host line in ~/.ssh/known_hosts")
        fi
        if [[ -e $ST_RUNTIME/porelo-planner/planner.json ]]; then
            left+=("$ST_RUNTIME/porelo-planner/planner.json")
        fi
        # Everything else the run made lies in its directory; the caller's source and executable stay.
        find "$ST_DIR" -mindepth 1 -maxdepth 1 ! -name source ! -name porelo-planner ! -name run.lock \
            -exec rm -rf -- {} + 2>/dev/null || true
        if [[ -n $(find "$ST_DIR" -mindepth 1 -maxdepth 1 ! -name source ! -name porelo-planner ! -name run.lock) ]]; then
            left+=("files in $ST_DIR")
        fi
    fi
    if ((${#left[@]})); then
        st_say "left behind: $(
            IFS=';'
            printf '%s' "${left[*]}"
        )"
        exit 3
    fi
    exit "$status"
}

if [[ ${ST_LIBRARY:-0} != 1 ]]; then
    case ${1:-} in
    leftovers)
        found=$(st_leftovers)
        [[ -z $found ]] && exit 0
        printf '%s\n' "$found"
        exit 1
        ;;
    *)
        printf 'usage: story-test.sh leftovers; story scripts source this file\n' >&2
        exit 2
        ;;
    esac
fi
