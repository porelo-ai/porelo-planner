#!/usr/bin/env bash
# shellcheck disable=SC2329 # each step function is called through st_step
# Plays the test of story ST309, "A story takes the state the operator gives it", as a person plays it: at the
# command line in a clone of the project, and through the planner as the window saves and reads the board.
#
# Usage: st309.sh --dir DIR [--expect N=ISSUE]...   (see story-test.sh for the run's directory and exit codes)
#
# The fixture: a project repository st-project with its stories and a repository st-alpha with the issues, on a
# forge of the run's own. ST1 stands in for a story set by hand to Backlog while one issue is Implementing and one
# Finished; ST2 is moved through the planner; ST3 states no state and then gets one written into its head.

ST_STORY=ST309
ST_LIBRARY=1
# shellcheck source=story-test.sh
source "$(dirname "${BASH_SOURCE[0]}")/story-test.sh"

readonly PROJECT=st309
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

# story ID TITLE STATE|- ISSUE...
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
    test: "It is seen to work.", priority: "High", issues: $issues, tested: null
  } + (if $state == "-" then {} else {state: $state} end)')
\`\`\`
EOF
}

fixture() {
    st_repo "$ALPHA"
    st_copy "$ALPHA" issues/workflow.yml planner/app/src/test/resources/work/porelo-alpha/issues/workflow.yml
    printf '# Issues\n\nThe issues of %s.\n' "$ALPHA" | st_write "$ALPHA" issues/README.md
    issue AB01 "The First Step" Implementing
    issue AB02 "The Second Step" Finished
    issue AB03 "The Third Step" Backlog
    issue AB04 "The Fourth Step" Implementing
    issue AB05 "The Fifth Step" Estimated
    issue AB06 "The Sixth Step" Review
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
    story ST1 "A Story Set By Hand" Backlog AB01 AB02
    story ST2 "A Story Moved On The Board" - AB03 AB04
    story ST3 "A Story Its Issues Place" - AB05 AB06
    st_cli "$ST_DIR/work/$OWN" stories index stories || st_die "stories index of $OWN failed: $ST_BODY"
    st_cli "$ST_DIR/work/$ALPHA" issues check issues || st_die "issues check of $ALPHA failed: $ST_BODY"

    st_push "$ALPHA" "Add the issues"
    st_push "$OWN" "Add the project and its stories"
}

# The board's story as JSON: board_story ID.
board_story() {
    st_api GET "/projects/$PROJECT/stories"
    [[ $ST_STATUS == 200 ]] || {
        st_no "GET /projects/$PROJECT/stories answers 200" "$ST_STATUS $ST_BODY"
        return 1
    }
    REVISION=$(jq -r '.revision' <<<"$ST_BODY")
    jq -c --arg id "$1" '.stories[] | select(.id == $id)' <<<"$ST_BODY"
}

# The state an issue's head gives in a clone: head_state DIR ID.
head_state() {
    local file
    file=$(find "$1/issues" -maxdepth 1 -name "$2-*.md" | head -n 1)
    [[ -n $file ]] || {
        printf 'no file'
        return 0
    }
    st_head "$file" | jq -r '.state'
}

step_1() {
    local clones line
    clones=$(st_clones "$OWN" "$ALPHA") || st_no "a clone of $OWN and $ALPHA" "git clone failed" || return 1
    st_cli "$clones/$OWN" stories index stories || st_no "stories index stories exits 0" "$ST_BODY" || return 1
    line=$(grep -E '^WARN +ST1:' <<<"$ST_BODY" || true)
    [[ $line == *'stated Backlog'* && $line == *AB01*Implementing* && $line == *AB02*Finished* ]] ||
        st_no "a warning that ST1 stays Backlog naming AB01 Implementing and AB02 Finished" "${ST_BODY:-no output}" ||
        return 1
    st_is "ST1's head" Backlog "$(st_head "$clones/$OWN/stories/ST1-A-Story-Set-By-Hand.md" | jq -r '.state')" || return 1
    st_is "ST1's row in stories/README.md" Backlog "$(st_section "$clones/$OWN/stories/README.md" st1)" || return 1
    st_is "the index leaves the clone unchanged" "" "$(git -C "$clones/$OWN" status --porcelain)"
}

step_2() {
    board_story ST2 >/dev/null || return 1
    local moves
    moves=$(jq -nc --arg r "$REVISION" '{revision: $r, moves: [{story: "ST2", to: "Review"}]}')
    st_api POST "/projects/$PROJECT/moves" "$moves"
    st_is "the unconfirmed move's status" 409 "$ST_STATUS" || return 1
    st_is "the refusal" urn:porelo-planner:StoryStateMismatch "$(jq -r '.type' <<<"$ST_BODY")" || return 1
    local named
    named=$(jq -r '.parameters.issues // ""' <<<"$ST_BODY")
    [[ $named == *AB04:Implementing* && $named != *AB03* ]] ||
        st_no "the refusal names AB04:Implementing, the issue that does not fit, and not AB03" "$named" || return 1
    st_api POST "/projects/$PROJECT/moves" "$(jq -c '. + {confirm: true}' <<<"$moves")"
    st_is "the confirmed move's status" 200 "$ST_STATUS" || st_no "$ST_ASKED" "$ST_STATUS $ST_BODY"
}

step_3() {
    local story
    story=$(board_story ST2) || return 1
    st_is "ST2 on the board" "Review true" "$(jq -r '"\(.state) \(.stateGiven)"' <<<"$story")" || return 1
    st_is "ST2's issues that do not fit" '["AB04"]' "$(jq -c '.misfits' <<<"$story")"
}

step_4() {
    local clones
    clones=$(st_clones "$OWN" "$ALPHA") || st_no "a clone of $OWN and $ALPHA" "git clone failed" || return 1
    st_is "ST2's head" Review \
        "$(st_head "$clones/$OWN/stories/ST2-A-Story-Moved-On-The-Board.md" | jq -r '.state // "none"')" || return 1
    st_is "ST2's row in stories/README.md" Review "$(st_section "$clones/$OWN/stories/README.md" st2)" || return 1
    # A story moves its least advanced issues, so AB03 goes along; the issue that did not fit keeps its state.
    st_is "the heads of AB01 AB02 AB04" "Implementing Finished Implementing" \
        "$(head_state "$clones/$ALPHA" AB01) $(head_state "$clones/$ALPHA" AB02) $(head_state "$clones/$ALPHA" AB04)" ||
        return 1
    st_is "AB03's head, moved with its story" Review "$(head_state "$clones/$ALPHA" AB03)"
}

step_5() {
    local story clones
    story=$(board_story ST3) || return 1
    st_is "ST3 on the board" "Estimated false" "$(jq -r '"\(.state) \(.stateGiven)"' <<<"$story")" || return 1
    clones=$(st_clones "$OWN") || st_no "a clone of $OWN" "git clone failed" || return 1
    st_is "ST3's row in stories/README.md" Estimated "$(st_section "$clones/$OWN/stories/README.md" st3)"
}

step_6() {
    local clones file story
    clones=$(st_clones "$OWN") || st_no "a clone of $OWN" "git clone failed" || return 1
    file=$clones/$OWN/stories/ST3-A-Story-Its-Issues-Place.md
    # As a person edits it: one line added to the head, beside the priority.
    sed -i 's/^  "priority": "High",$/&\n  "state": "Analyzed",/' "$file"
    st_is "the head edited by hand" Analyzed "$(st_head "$file" | jq -r '.state')" || return 1
    git -C "$clones/$OWN" commit -q -am "Give ST3 its state by hand" &&
        git -C "$clones/$OWN" push -q origin main || st_no "the edited head pushed" "git push failed" || return 1
    st_api POST "/projects/$PROJECT/refresh"
    st_is "POST /refresh" 200 "$ST_STATUS" || st_no "$ST_ASKED" "$ST_STATUS $ST_BODY" || return 1
    story=$(board_story ST3) || return 1
    st_is "ST3 on the board" "Analyzed true" "$(jq -r '"\(.state) \(.stateGiven)"' <<<"$story")"
}

st_setup "$@"
fixture
st_planner "$PROJECT" "$OWN"
st_step 1 "stories index keeps a stated Backlog and warns naming the issues" step_1
st_step 2 "a move to a state the issues do not fit is refused, then saved when confirmed" step_2
st_step 3 "the board shows the given state and the issues that do not fit" step_3
st_step 4 "a fresh clone holds the story's state, and the issue that did not fit kept its own" step_4
st_step 5 "a story with no state stands in its issues' earliest open state" step_5
st_step 6 "a state written into a head by hand stands on the board after a refresh" step_6
st_finish
