#!/usr/bin/env bash
# shellcheck disable=SC2329 # each step function is called through st_step
# Plays the test of story ST165, "Every story and issue read and written robustly", for a board a person edited by
# hand: at the command line in a clone of the project, and through the planner as the window offers, previews and
# confirms the correction of each warning.
#
# Usage: st165.sh --dir DIR [--expect N=ISSUE]...   (see story-test.sh for the run's directory and exit codes)
#
# The fixture: a project repository st-project with two stories and a repository st-alpha with their issues, on a
# forge of the run's own. A person then writes a state the workflow does not know into ST1's head and into AB03's,
# by hand; ST1 is set right through a correction and AB03 through a move, each previewed and confirmed.

ST_STORY=ST165
ST_LIBRARY=1
# shellcheck source=story-test.sh
source "$(dirname "${BASH_SOURCE[0]}")/story-test.sh"

readonly PROJECT=st165
readonly OWN=st-project
readonly ALPHA=st-alpha

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

# story ID TITLE STATE ISSUE...
story() {
    local id=$1 title=$2 state=$3 issues
    shift 3
    issues=$(printf '%s\n' "$@" | jq -R --arg r "$ALPHA" '$r + "#" + .' | jq -s -c .)
    st_write "$OWN" "stories/$id-${title// /-}.md" <<EOF
# $id — $title

## Data

\`\`\`json
$(jq -n --arg id "$id" --arg title "$title" --arg state "$state" --argjson issues "$issues" '{
    "$schema": "story-2.json", id: $id, title: $title, gain: ("A person gains " + $title + "."),
    test: "It is seen to work.", priority: "High", state: $state, issues: $issues, tested: null
  }')
\`\`\`
EOF
}

fixture() {
    st_repo "$ALPHA"
    st_copy "$ALPHA" issues/workflow.yml planner/app/src/test/resources/work/porelo-alpha/issues/workflow.yml
    printf '# Issues\n\nThe issues of %s.\n' "$ALPHA" | st_write "$ALPHA" issues/README.md
    issue AB01 "The First Step" Backlog
    issue AB02 "The Second Step" Backlog
    issue AB03 "The Third Step" Estimated
    st_cli "$ST_DIR/work/$ALPHA" issues index issues || st_die "issues index of $ALPHA failed: $ST_BODY"

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
    st_write "$OWN" stories/work.yml <<'EOF'
work:
  Implementing:
    from: [Backlog]
    done: Review
    per: repository
    modes: [UNATTENDED]
    order: "Build {issues}."
    models: {claude: claude-opus-5-5}
EOF
    printf '# Stories\n' | st_write "$OWN" stories/README.md
    story ST1 "A Story Set Right" Backlog AB01 AB02
    story ST2 "A Story Whose Issue Is Set Right" Estimated AB03
    st_cli "$ST_DIR/work/$OWN" stories index stories || st_die "stories index of $OWN failed: $ST_BODY"
    st_cli "$ST_DIR/work/$ALPHA" issues check issues || st_die "issues check of $ALPHA failed: $ST_BODY"

    st_push "$ALPHA" "Add the issues"
    st_push "$OWN" "Add the project and its stories"
}

# The board's stories answer, its revision kept in REVISION.
board() {
    st_api GET "/projects/$PROJECT/stories"
    [[ $ST_STATUS == 200 ]] || {
        st_no "GET /projects/$PROJECT/stories answers 200" "$ST_STATUS $ST_BODY"
        return 1
    }
    REVISION=$(jq -r '.revision' <<<"$ST_BODY")
}

# The item of an id anywhere in the last board answer, a story or an issue: item ID.
item() {
    jq -c --arg id "$1" '[.. | objects | select(.id? == $id and has("warnings"))][0]' <<<"$ST_BODY"
}

# The state an item's head gives in a clone: head_state DIR FOLDER ID.
head_state() {
    local file
    file=$(find "$1/$2" -maxdepth 1 -name "$3-*.md" | head -n 1)
    [[ -n $file ]] || {
        printf 'no file'
        return 0
    }
    st_head "$file" | jq -r '.state'
}

step_1() {
    local clones
    clones=$(st_clones "$OWN" "$ALPHA") || st_no "a clone of $OWN and $ALPHA" "git clone failed" || return 1
    # As a person edits them: the state line of each head written over with a state no workflow knows.
    sed -i 's/^  "state": "Backlog",$/  "state": "Parked",/' "$clones/$OWN/stories/ST1-A-Story-Set-Right.md"
    sed -i 's/^  "state": "Estimated",$/  "state": "Waiting",/' "$clones/$ALPHA/issues/AB03-The-Third-Step.md"
    st_is "ST1's head edited by hand" Parked "$(head_state "$clones/$OWN" stories ST1)" || return 1
    st_is "AB03's head edited by hand" Waiting "$(head_state "$clones/$ALPHA" issues AB03)" || return 1
    git -C "$clones/$ALPHA" commit -q -am "Park AB03 by hand" && git -C "$clones/$ALPHA" push -q origin main &&
        git -C "$clones/$OWN" commit -q -am "Park ST1 by hand" && git -C "$clones/$OWN" push -q origin main ||
        st_no "the edited heads pushed" "git push failed" || return 1
    st_api POST "/projects/$PROJECT/refresh"
    st_is "POST /refresh" 200 "$ST_STATUS" || st_no "$ST_ASKED" "$ST_STATUS $ST_BODY"
}

step_2() {
    local story issue
    board || return 1
    story=$(item ST1)
    issue=$(item AB03)
    st_is "ST1's warning and its correction" "StateOutsideWorkflow StateStated" \
        "$(jq -r '.warnings[0] | "\(.kind) \(.correction.kind // "none")"' <<<"$story")" || return 1
    st_is "AB03's warning and its correction" "StateOutsideWorkflow Move" \
        "$(jq -r '.warnings[0] | "\(.kind) \(.correction.kind // "none")"' <<<"$issue")" || return 1
    jq -e '.warnings[0].correction.states | index("Backlog")' <<<"$story" >/dev/null ||
        st_no "Backlog among the states offered for ST1" "$(jq -c '.warnings[0].correction.states' <<<"$story")" ||
        return 1
    jq -e '.warnings[0].correction.states | index("Estimated")' <<<"$issue" >/dev/null ||
        st_no "Estimated among the states offered for AB03" "$(jq -c '.warnings[0].correction.states' <<<"$issue")"
}

step_3() {
    local asked digest
    board || return 1
    asked=$(jq -nc --arg r "$REVISION" '{revision: $r, story: "ST1", warning: "StateOutsideWorkflow", state: "Backlog"}')
    st_api POST "/projects/$PROJECT/corrections/preview" "$asked"
    st_is "the correction's preview" 200 "$ST_STATUS" || st_no "$ST_ASKED" "$ST_STATUS $ST_BODY" || return 1
    st_is "what the preview would write" "ST1 Parked Backlog" \
        "$(jq -r '"\(.story) \(.from) \(.to)"' <<<"$ST_BODY")" || return 1
    digest=$(jq -r '.digest' <<<"$ST_BODY")
    st_api POST "/projects/$PROJECT/corrections" "$(jq -c --arg d "$digest" '. + {digest: $d, confirm: true}' <<<"$asked")"
    st_is "the confirmed correction" 200 "$ST_STATUS" || st_no "$ST_ASKED" "$ST_STATUS $ST_BODY" || return 1
    board || return 1
    st_is "ST1 on the board, its warning lifted" "Backlog 0" \
        "$(item ST1 | jq -r '"\(.state) \(.warnings | length)"')"
}

step_4() {
    local moves digest
    board || return 1
    moves=$(jq -nc --arg r "$REVISION" '{revision: $r, moves: [{issue: "AB03", to: "Estimated"}]}')
    st_api POST "/projects/$PROJECT/moves/preview" "$moves"
    st_is "the move's preview" 200 "$ST_STATUS" || st_no "$ST_ASKED" "$ST_STATUS $ST_BODY" || return 1
    digest=$(jq -r '.digest' <<<"$ST_BODY")
    st_api POST "/projects/$PROJECT/moves" "$(jq -c --arg d "$digest" '. + {digest: $d, confirm: true}' <<<"$moves")"
    st_is "the confirmed move" 200 "$ST_STATUS" || st_no "$ST_ASKED" "$ST_STATUS $ST_BODY" || return 1
    board || return 1
    st_is "AB03 on the board, its warning lifted" "Estimated 0" \
        "$(item AB03 | jq -r '"\(.state) \(.warnings | length)"')"
}

step_5() {
    local clones
    clones=$(st_clones "$OWN" "$ALPHA") || st_no "a clone of $OWN and $ALPHA" "git clone failed" || return 1
    st_is "ST1's head" Backlog "$(head_state "$clones/$OWN" stories ST1)" || return 1
    st_is "ST1's row in stories/README.md" Backlog "$(st_section "$clones/$OWN/stories/README.md" st1)" || return 1
    st_is "AB03's head" Estimated "$(head_state "$clones/$ALPHA" issues AB03)" || return 1
    st_is "the heads of AB01 AB02, which the correction left" "Backlog Backlog" \
        "$(head_state "$clones/$ALPHA" issues AB01) $(head_state "$clones/$ALPHA" issues AB02)"
}

st_setup "$@"
fixture
st_planner "$PROJECT" "$OWN"
st_step 1 "a state no workflow knows is written into a story's head and an issue's by hand" step_1
st_step 2 "the board warns on both and offers each its one correction" step_2
st_step 3 "the story is set right through a previewed and confirmed correction" step_3
st_step 4 "the issue is set right through a previewed and confirmed move" step_4
st_step 5 "a fresh clone holds both states, and the story's issues kept their own" step_5
st_finish
