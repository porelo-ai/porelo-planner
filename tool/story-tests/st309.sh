#!/usr/bin/env bash
# shellcheck disable=SC2329 # each step function is called through st_step
# Plays the test of story ST309, "A story takes the state the operator gives it", as a person plays it now: at the
# command line in a clone of the project, and through the planner as the window saves and reads the board. An open
# story keeps no state of its own: it follows its issues, and a person only closes it and reopens it.
#
# Usage: st309.sh --dir DIR [--expect N=ISSUE]...   (see story-test.sh for the run's directory and exit codes)
#
# The fixture: a project repository st-project with its stories and a repository st-alpha with the issues, on a
# forge of the run's own. ST1 stands in for a story set by hand to Backlog while one issue is Implementing and one
# Finished; ST2, one issue in Backlog and one Implementing, is refused a move between open states, then rejected and
# reopened through the planner; ST3, one issue Estimated and one in Review, states no state and then gets an open one
# written into its head by hand, which the board does not follow.

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
roles:
  author: {end: push, workspace: own, rights: [push, reply]}
work:
  Implementing:
    from: [Backlog]
    done: Review
    per: repository
    modes: [UNATTENDED]
    order: "Build {issues}."
    models: {claude: claude-opus-5-5}
    role: author
EOF
    printf '# Stories\n' | st_write "$OWN" stories/README.md
    story ST1 "A Story Set By Hand" Backlog AB01 AB02
    story ST2 "A Story Moved On The Board" - AB03 AB04
    story ST3 "A Story Its Issues Place" - AB05 AB06

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
    jq -c --arg id "$1" '.stories[] | select(.id == $id)' <<<"$ST_BODY"
}

# Previews a batch and saves it with the preview's digest and the versions of its targets, confirmed: save MOVES.
save() {
    local digest seen
    st_api POST "/projects/$PROJECT/moves/preview" "$1"
    st_is "the move's preview" 200 "$ST_STATUS" || st_no "$ST_ASKED" "$ST_STATUS $ST_BODY" || return 1
    digest=$(jq -r '.digest' <<<"$ST_BODY")
    seen=$(st_seen "$ST_BODY")
    st_api POST "/projects/$PROJECT/moves" \
        "$(jq -c --arg d "$digest" --argjson s "$seen" '. + {digest: $d, seen: $s, confirm: true}' <<<"$1")"
    st_is "the confirmed move" 200 "$ST_STATUS" || st_no "$ST_ASKED" "$ST_STATUS $ST_BODY"
}

step_2() {
    local story
    story=$(board_story ST1) || return 1
    # The state stated by hand is not the board's: an open story stands where its issues give.
    st_is "ST1 on the board" Implementing "$(jq -r '.state' <<<"$story")" || return 1
    story=$(board_story ST2) || return 1
    # An issue being built makes its story built, the other still in Backlog.
    st_is "ST2 on the board" Implementing "$(jq -r '.state' <<<"$story")"
}

step_3() {
    local moves
    board_story ST2 >/dev/null || return 1
    moves='{"moves": [{"story": "ST2", "to": "Review"}]}'
    st_api POST "/projects/$PROJECT/moves/preview" "$moves"
    st_is "the move's status" 422 "$ST_STATUS" || return 1
    st_is "the refusal" urn:porelo-planner:MoveNotAllowed "$(jq -r '.type' <<<"$ST_BODY")" || return 1
    board_story ST2 >/dev/null || return 1
    st_is "ST2 still on the board" Implementing "$(board_story ST2 | jq -r '.state')"
}

step_4() {
    local story
    board_story ST2 >/dev/null || return 1
    save '{"moves": [{"story": "ST2", "to": "Rejected"}]}' || return 1
    story=$(board_story ST2) || return 1
    st_is "ST2 on the board" Rejected "$(jq -r '.state' <<<"$story")" || return 1
    st_is "ST2's day of a passed test, none for a rejected story" null "$(jq -r '.closedAt' <<<"$story")"
}

step_5() {
    local clones
    clones=$(st_clones "$OWN" "$ALPHA") || st_no "a clone of $OWN and $ALPHA" "git clone failed" || return 1
    st_is "ST2's head" Rejected \
        "$(st_head "$clones/$OWN/stories/ST2-A-Story-Moved-On-The-Board.md" | jq -r '.state // "none"')" || return 1
    st_is "ST2's row in stories/README.md" Rejected "$(st_section "$clones/$OWN/stories/README.md" st2)" || return 1
    # A rejected story closes each of its open issues with it; a closed issue keeps only its row in the index.
    st_is "the rows of AB03 AB04" "Rejected Rejected" \
        "$(st_closed "$clones/$ALPHA/issues" AB03) $(st_closed "$clones/$ALPHA/issues" AB04)"
}

step_6() {
    local story clones
    board_story ST2 >/dev/null || return 1
    save '{"moves": [{"story": "ST2", "to": "Backlog"}]}' || return 1
    story=$(board_story ST2) || return 1
    # Reopened with every issue rejected, it has none to follow: it stands in the state its reopen names, kept in its
    # head as a story without issues keeps one.
    st_is "ST2 on the board" Backlog "$(jq -r '.state' <<<"$story")" || return 1
    st_is "ST2 unwarned" "[]" "$(jq -c '.warnings' <<<"$story")" || return 1
    clones=$(st_clones "$OWN") || st_no "a clone of $OWN" "git clone failed" || return 1
    st_is "ST2's head, the reopen's state" Backlog \
        "$(st_head "$clones/$OWN/stories/ST2-A-Story-Moved-On-The-Board.md" | jq -r '.state // "none"')"
}

step_7() {
    local clones file story
    story=$(board_story ST3) || return 1
    # No issue being built: AB06 in Review counts as Backlog, which stands after AB05's Estimated.
    st_is "ST3 on the board" Backlog "$(jq -r '.state' <<<"$story")" || return 1
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
    st_is "ST3 on the board, still where its issues give" Backlog "$(jq -r '.state' <<<"$story")"
}

st_setup "$@"
fixture
st_planner "$PROJECT" "$OWN"
st_step 2 "an open story stands where its issues give, whatever its head states" step_2
st_step 3 "a move of an open story to another open state is refused" step_3
st_step 4 "a story rejected through the planner stands closed" step_4
st_step 5 "a fresh clone holds the story's close, and its open issues closed with it" step_5
st_step 6 "a story reopened with every issue rejected stands in the state its reopen names" step_6
st_step 7 "an open state written into a head by hand is not followed by the board" step_7
st_finish
