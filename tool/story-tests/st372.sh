#!/usr/bin/env bash
# shellcheck disable=SC2329 # each step function is called through st_step
# Plays the test of story ST372, "A project's own roles carry its tasks", without Porelo: the script plays the window,
# which orders a story's work and reports each task's events, and shows that the planner serves and records every task
# by its role's name and parts, the reader role of the project's work rules included.
#
# Usage: st372.sh --dir DIR [--expect N=ISSUE]...   (see story-test.sh for the run's directory and exit codes)
#
# The fixture: a project repository st-project and a repository st-alpha with the issues, on a forge of the run's own.
# The project's work rules define the roles author, which pushes, and reader, which reads the landed work read only and
# reports findings, and the rules Implementing, Reading and Reworking by them. ST1 holds one issue, AB01, in Backlog.
# Its work is ordered into Implementing, the author's task ends with its work landed, and the reader that offers is
# started, refused under the author's task name and then under a name of its own, and ends with no findings.

ST_STORY=ST372
ST_LIBRARY=1
# shellcheck source=story-test.sh
source "$(dirname "${BASH_SOURCE[0]}")/story-test.sh"

readonly PROJECT=st372
readonly OWN=st-project
readonly ALPHA=st-alpha
readonly AUTHOR_TASK=st-author
readonly READER_TASK=st-reader
# Where the author's work landed, as Porelo's task row names it.
readonly LANDING='[{"branch": "porelo/st-author", "ref": "refs/landed/st-author/porelo/st-author",
    "remote": "origin/landed/st-author/porelo/st-author", "commit": "0123456789abcdef0123456789abcdef01234567"}]'
AUTHOR_OFFER=
READER_OFFER=
SEEN=

issue() {
    local id=$1 title=$2 state=$3
    st_write "$ALPHA" "issues/$id-${title// /-}.md" <<EOF
# $id — $title

\`\`\`json
{
  "\$schema": "issue-1.json",
  "id": "$id",
  "title": "$title",
  "state": "$state",
  "size": "S",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "$title is done"
}
\`\`\`

**What must be true.** It is done.
EOF
}

story() {
    local id=$1 title=$2 issues
    shift 2
    issues=$(printf '%s\n' "$@" | jq -R --arg r "$ALPHA" '$r + "#" + .' | jq -s -c .)
    st_write "$OWN" "stories/$id-${title// /-}.md" <<EOF
# $id — $title

## Data

\`\`\`json
$(jq -n --arg id "$id" --arg title "$title" --argjson issues "$issues" '{
    "$schema": "story-2.json", id: $id, title: $title, gain: ("A person gains " + $title + "."),
    test: "It is seen to work.", priority: "High", issues: $issues, tested: null
  }')
\`\`\`
EOF
}

fixture() {
    st_repo "$ALPHA"
    st_copy "$ALPHA" issues/workflow.yml planner/app/src/test/resources/work/porelo-alpha/issues/workflow.yml
    printf '# Issues\n\nThe issues of %s.\n' "$ALPHA" | st_write "$ALPHA" issues/README.md
    issue AB01 "The First Step" Backlog

    st_repo "$OWN"
    st_write "$OWN" project.yml <<EOF
project:
    name: "$PROJECT"
    central: "$(st_url "$OWN")"

repositories:
    alpha:
        central: "$(st_url "$ALPHA")"
        issues: ["AB"]
EOF
    st_copy "$OWN" stories/workflow.yml planner/app/src/test/resources/work/porelo-sample/stories/workflow.yml
    st_copy "$OWN" issues/workflow.yml planner/app/src/test/resources/work/porelo-sample/issues/workflow.yml
    printf "# Issues\n\nThe project's own issues.\n" | st_write "$OWN" issues/README.md
    # The reader's role is the project's own: the planner knows no role by its name.
    st_write "$OWN" stories/work.yml <<'EOF'
roles:
  author: {end: push, workspace: own, rights: [push, reply]}
  reader: {end: findings, workspace: readOnly, readLanded: true, rights: [report]}
work:
  Implementing:
    from: [Backlog]
    done: Review
    then: Reading
    per: repository
    modes: [UNATTENDED]
    order: "Build {issues}."
    models: {claude: claude-opus-5-5}
    role: author
  Reading:
    from: [Implementing, Reworking]
    done: Review
    blocked: Reworking
    per: repository
    modes: [UNATTENDED]
    order: "Read what {tasks} built for {issues}."
    models: {claude: claude-opus-5-5}
    role: reader
  Reworking:
    from: [Reading]
    done: Review
    then: Reading
    per: repository
    modes: [UNATTENDED]
    order: "Fix what was found in {issues}."
    models: {claude: claude-opus-5-5}
    role: author
EOF
    printf '# Stories\n' | st_write "$OWN" stories/README.md
    story ST1 "A Story Of Two Roles" AB01

    st_push "$ALPHA" "Add the issues"
    st_push "$OWN" "Add the project and its stories"
}

# One call that must answer a status: call STATUS METHOD PATH [BODY]; fails the step with the answer otherwise.
call() {
    local want=$1
    shift
    st_api "$@"
    [[ $ST_STATUS == "$want" ]] || st_no "$1 $2 answers $want" "$ST_STATUS $ST_BODY"
}

# The versions of every item the board's answer names, as the window keeps them from its last read: the body's `seen`,
# kept in SEEN.
read_seen() {
    call 200 GET "/projects/$PROJECT/stories" || return 1
    SEEN=$(jq -c '[.. | objects | select((.id | type) == "string" and (.version | type) == "string") | {(.id): .version}]
        | add // {}' <<<"$ST_BODY")
}

# AB01's state on the board.
issue_state() {
    call 200 GET "/projects/$PROJECT/stories" || return 1
    jq -r '.stories[].issues[] | select(.id == "AB01") | .state' <<<"$ST_BODY"
}

# A task event as the window reports it: report OFFER EVENT CONTAINER [ENDED-JSON].
report() {
    jq -nc --arg offer "$1" --arg event "$2" --arg container "$3" --argjson ended "${4:-null}" \
        '{offer: $offer, event: $event, container: $container, machine: "story-test"}
         + (if $ended == null then {} else {ended: $ended} end)'
}

# A task's end that finished with everything pushed: ended ANSWER [LANDINGS].
ended() {
    jq -nc --arg answer "$1" --argjson landed "${2:-[]}" \
        '{at: "2026-01-01T00:00:00Z", finished: true, why: "", answer: $answer, unpushed: 0}
         + (if ($landed | length) > 0 then {landed: $landed} else {} end)'
}

# Holds when none of the members is in the order: absent ORDER MEMBER...
absent() {
    local order=$1 member
    shift
    for member in "$@"; do
        if jq -e --arg m "$member" 'has($m)' <<<"$order" >/dev/null; then
            st_no "no $member in the order" "$order"
            return 1
        fi
    done
}

step_1() {
    call 200 GET "/projects/$PROJECT/workflow" || return 1
    st_is "the work rules' faults" "[]" "$(jq -c '.faults' <<<"$ST_BODY")" || return 1
    st_is "the sound work rules" "Implementing,Reading,Reworking" \
        "$(jq -r '[.work[].state] | sort | join(",")' <<<"$ST_BODY")"
}

step_2() {
    local order body
    read_seen || return 1
    body=$(jq -nc --arg p "$PROJECT" --argjson seen "$SEEN" \
        '{project: $p, story: "ST1", to: "Implementing", mode: "UNATTENDED", seen: $seen}') || return 1
    call 200 POST "/projects/$PROJECT/stories/ST1/work" "$body" || return 1
    st_is "the start orders" 1 "$(jq '.tasks | length' <<<"$ST_BODY")" || return 1
    order=$(jq -c '.tasks[0]' <<<"$ST_BODY")
    AUTHOR_OFFER=$(jq -r '.offer' <<<"$order")
    st_is "the order's role" author "$(jq -r '.role.name' <<<"$order")" || return 1
    st_is "the order's kind" work "$(jq -r '.kind' <<<"$order")" || return 1
    st_is "the order's rights" '["push","reply"]' "$(jq -c '.rights' <<<"$order")" || return 1
    st_is "the order's readLanded" '[]' "$(jq -c '.readLanded' <<<"$order")" || return 1
    st_is "the order's projectServices" true "$(jq -c '.projectServices' <<<"$order")" || return 1
    # Only a task of mode AGENT whose role pushes opens the window's terminal; this one runs unattended.
    absent "$order" opensTerminal readOnly noGate place task ownTask writeAs
}

# The author's task starts under its own name and ends with its work landed; the answer offers the reader.
step_3() {
    local order
    call 200 POST "/projects/$PROJECT/tasks/$AUTHOR_TASK/events" \
        "$(report "$AUTHOR_OFFER" STARTED "box-$AUTHOR_TASK")" || return 1
    call 200 POST "/projects/$PROJECT/tasks/$AUTHOR_TASK/events" \
        "$(report "$AUTHOR_OFFER" ENDED "box-$AUTHOR_TASK" "$(ended "Built" "$LANDING")")" || return 1
    st_is "the offers after the end" 1 "$(jq '.tasks | length' <<<"$ST_BODY")" || return 1
    order=$(jq -c '.tasks[0]' <<<"$ST_BODY")
    READER_OFFER=$(jq -r '.offer' <<<"$order")
    st_is "AB01 on the board" Reading "$(issue_state)" || return 1
    st_is "the offer's role" reader "$(jq -r '.role.name' <<<"$order")" || return 1
    st_is "the role's end" REPORTS_FINDINGS "$(jq -r '.role.endKind' <<<"$order")" || return 1
    st_is "the role's workspace" readOnly "$(jq -r '.role.workspace' <<<"$order")" || return 1
    st_is "the order's readOnly" true "$(jq -c '.readOnly' <<<"$order")" || return 1
    st_is "the order's rights" '["report"]' "$(jq -c '.rights' <<<"$order")" || return 1
    st_is "the order's readLanded" "[\"$AUTHOR_TASK\"]" "$(jq -c '.readLanded' <<<"$order")" || return 1
    st_is "the order's projectServices" true "$(jq -c '.projectServices' <<<"$order")" || return 1
    absent "$order" place ownTask task || return 1
    [[ $(jq -r '.prompt' <<<"$order") == *'Findings:'* ]] ||
        st_no "the prompt holds the findings instruction" "$(jq -r '.prompt' <<<"$order")"
}

# Four eyes: the reader may not be started under the task name of the work it reads.
step_4() {
    call 409 POST "/projects/$PROJECT/tasks/$AUTHOR_TASK/events" \
        "$(report "$READER_OFFER" STARTED "box-$READER_TASK")" || return 1
    [[ $(jq -r '.type' <<<"$ST_BODY") == *:TaskEventConflict ]] ||
        st_no "a TaskEventConflict" "$ST_BODY" || return 1
    call 200 GET "/projects/$PROJECT/stories/ST1/work" || return 1
    st_is "the reader's tasks after the refusal" 0 \
        "$(jq '[.work.tasks[] | select(.role.name == "reader")] | length' <<<"$ST_BODY")"
}

step_5() {
    local line part
    # The offer stood after the refusal, or this start is refused too.
    call 200 POST "/projects/$PROJECT/tasks/$READER_TASK/events" \
        "$(report "$READER_OFFER" STARTED "box-$READER_TASK")" || return 1
    line=$(PLANNER_PROJECT=$PROJECT PLANNER_URL=$ST_URL PLANNER_TOKEN=$ST_TOKEN \
        bash "$ST_SRC/tool/planner-probe.sh" work ST1 started 2>&1) ||
        st_no "the probe finds the run started" "$line" || return 1
    line=$(grep -F "task $READER_TASK " <<<"$line" || true)
    for part in role=reader workspace=readOnly rights=report "readLanded=$AUTHOR_TASK"; do
        [[ " $line " == *" $part "* ]] || st_no "the reader's line holds $part" "${line:-no line for $READER_TASK}" ||
            return 1
    done
}

step_6() {
    call 200 POST "/projects/$PROJECT/tasks/$READER_TASK/events" \
        "$(report "$READER_OFFER" ENDED "box-$READER_TASK" "$(ended $'Read it.\nFindings:')")" || return 1
    st_is "AB01 on the board" Review "$(issue_state)"
}

st_setup "$@"
fixture
st_planner "$PROJECT" "$OWN"
st_step 1 "the board loads with no fault of the project's work rules" step_1
st_step 2 "ordering the work into Implementing offers one task by the author role" step_2
st_step 3 "the author's end with its work landed offers the reader role's task" step_3
st_step 4 "the reader is refused under the author's task name" step_4
st_step 5 "the reader starts under a name of its own and the probe shows it by its role" step_5
st_step 6 "the reader's end with no findings moves AB01 to Review" step_6
st_finish
