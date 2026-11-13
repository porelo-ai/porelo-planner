#!/usr/bin/env bash
# shellcheck disable=SC2329 # each step function is called through st_step
# Plays the test of story ST315, "The planner's Git adapter alone handles stories and issues", as a person plays it:
# through the planner as the window moves an issue and hands in an idea, and at the command line in fresh clones of a
# repository whose build has no story or issue tooling - it has no build at all.
#
# Usage: st315.sh --dir DIR [--expect N=ISSUE]...   (see story-test.sh for the run's directory and exit codes)
#
# The fixture: a project repository st-project with one story, ST1, and a repository st-alpha holding only its
# issues, AB01 and AB02 in Analyzed, on a forge of the run's own. AB01 is moved to Estimated, an idea becomes AB03,
# and AB02's head is then broken by hand.

ST_STORY=ST315
ST_LIBRARY=1
# shellcheck source=story-test.sh
source "$(dirname "${BASH_SOURCE[0]}")/story-test.sh"

readonly PROJECT=st315
readonly OWN=st-project
readonly ALPHA=st-alpha
readonly IDEA_TITLE="An Idea Handed In"
FIXTURE_HEAD=
NEW_ID=

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

fixture() {
    st_repo "$ALPHA"
    st_copy "$ALPHA" issues/workflow.yml planner/app/src/test/resources/work/porelo-alpha/issues/workflow.yml
    printf '# Issues\n\nThe issues of %s.\n' "$ALPHA" | st_write "$ALPHA" issues/README.md
    issue AB01 "The First Step" Analyzed
    issue AB02 "The Second Step" Analyzed
    st_cli "$ST_DIR/work/$ALPHA" issues index issues || st_die "issues index of $ALPHA failed: $ST_BODY"
    st_cli "$ST_DIR/work/$ALPHA" issues check issues || st_die "issues check of $ALPHA failed: $ST_BODY"

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
    st_write "$OWN" stories/ST1-A-Story-Of-Plain-Issues.md <<EOF
# ST1 — A Story Of Plain Issues

## Data

\`\`\`json
{
  "\$schema": "story-2.json",
  "id": "ST1",
  "title": "A Story Of Plain Issues",
  "gain": "A person gains a story of plain issues.",
  "test": "It is seen to work.",
  "priority": "High",
  "issues": ["$ALPHA#AB01", "$ALPHA#AB02"],
  "tested": null
}
\`\`\`
EOF
    st_cli "$ST_DIR/work/$OWN" stories index stories || st_die "stories index of $OWN failed: $ST_BODY"

    st_push "$ALPHA" "Add the issues"
    st_push "$OWN" "Add the project and its story"
    FIXTURE_HEAD=$(git -C "$ST_DIR/work/$ALPHA" rev-parse HEAD)
}

# The board's revision, as the window holds it from its last read.
revision() {
    st_api GET "/projects/$PROJECT/stories"
    [[ $ST_STATUS == 200 ]] || {
        st_no "GET /projects/$PROJECT/stories answers 200" "$ST_STATUS $ST_BODY"
        return 1
    }
    REVISION=$(jq -r '.revision' <<<"$ST_BODY")
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
    local clones
    clones=$(st_clones "$ALPHA") || st_no "a clone of $ALPHA" "git clone failed" || return 1
    # No pom.xml, wrapper, Gradle, npm or make file: what the repository tracks at its top is its issues alone.
    st_is "what $ALPHA tracks at its top" issues "$(git -C "$clones/$ALPHA" ls-tree --name-only HEAD | paste -sd ' ')"
}

step_2() {
    revision || return 1
    st_api POST "/projects/$PROJECT/moves" \
        "$(jq -nc --arg r "$REVISION" '{revision: $r, moves: [{issue: "AB01", to: "Estimated"}]}')"
    st_is "the move's status" 200 "$ST_STATUS" || st_no "$ST_ASKED" "$ST_STATUS $ST_BODY" || return 1
    st_is "the issues the move names" '["AB01"]' "$(jq -c '.moved' <<<"$ST_BODY")"
}

step_3() {
    revision || return 1
    st_api POST "/projects/$PROJECT/issues" "$(jq -nc --arg r "$REVISION" --arg repo "$ALPHA" --arg t "$IDEA_TITLE" \
        '{repository: $repo, title: $t, mustBeTrue: "A person sees the idea as an issue.", revision: $r}')"
    st_is "the new issue's status" 201 "$ST_STATUS" || st_no "$ST_ASKED" "$ST_STATUS $ST_BODY" || return 1
    NEW_ID=$(jq -r '.id' <<<"$ST_BODY")
    st_is "the new issue's id, the next free number" AB03 "$NEW_ID"
}

step_4() {
    local clones dir record subjects subject
    clones=$(st_clones "$ALPHA") || st_no "a clone of $ALPHA" "git clone failed" || return 1
    dir=$clones/$ALPHA
    st_is "AB01's head" Estimated "$(head_state "$dir" AB01)" || return 1
    [[ -f $dir/stats/AB01.jsonl ]] || st_no "AB01's stage record stats/AB01.jsonl" "no file" || return 1
    record=$(tail -n 1 "$dir/stats/AB01.jsonl" | jq -r '"\(.stage) \(.status_before) \(.status_after)"' 2>&1) || true
    st_is "AB01's last stage record" "move Analyzed Estimated" "$record" || return 1
    st_is "AB03's head" Idea "$(head_state "$dir" AB03)" || return 1
    st_is "AB03's title" "$IDEA_TITLE" \
        "$(st_head "$(find "$dir/issues" -maxdepth 1 -name 'AB03-*.md' | head -n 1)" | jq -r '.title')" || return 1
    st_is "the rows of AB01 and AB03 in issues/README.md" "Estimated Idea" \
        "$(st_section "$dir/issues/README.md" ab01) $(st_section "$dir/issues/README.md" ab03)" || return 1
    subjects=$(git -C "$dir" log --format=%s "$FIXTURE_HEAD..HEAD")
    [[ -n $subjects ]] || st_no "the planner's commits since the fixture" "none" || return 1
    while IFS= read -r subject; do
        [[ $subject == *' [skip ci]' ]] || st_no "every planning commit ends in [skip ci]" "$subject" || return 1
    done <<<"$subjects"
    st_cli "$dir" issues check issues || st_no "issues check issues exits 0" "$ST_BODY" || return 1
    [[ $ST_BODY == OK* ]] || st_no "issues check issues prints OK" "$ST_BODY"
}

step_5() {
    local clones file faults
    clones=$(st_clones "$ALPHA") || st_no "a clone of $ALPHA" "git clone failed" || return 1
    file=$clones/$ALPHA/issues/AB02-The-Second-Step.md
    # As a person edits it: the head's state replaced by one the workflow does not know.
    sed -i 's/^  "state": "Analyzed",$/  "state": "Pondering",/' "$file"
    st_is "the head edited by hand" Pondering "$(st_head "$file" | jq -r '.state')" || return 1
    git -C "$clones/$ALPHA" commit -q -am "Put AB02 in a state of its own" &&
        git -C "$clones/$ALPHA" push -q origin main || st_no "the edited head pushed" "git push failed" || return 1
    st_api POST "/projects/$PROJECT/refresh"
    st_is "POST /refresh" 200 "$ST_STATUS" || st_no "$ST_ASKED" "$ST_STATUS $ST_BODY" || return 1
    st_api GET "/projects/$PROJECT/workflow"
    st_is "GET /workflow" 200 "$ST_STATUS" || st_no "$ST_ASKED" "$ST_STATUS $ST_BODY" || return 1
    faults=$(jq -r '.faults[]' <<<"$ST_BODY")
    grep -q -F "$ALPHA: issues/AB02-The-Second-Step.md" <<<"$faults" ||
        st_no "a fault naming $ALPHA: issues/AB02-The-Second-Step.md" "${faults:-no fault}" || return 1
    st_api GET "/projects/$PROJECT/stories"
    st_is "GET /stories" 200 "$ST_STATUS" || st_no "$ST_ASKED" "$ST_STATUS $ST_BODY" || return 1
    st_is "ST1's issues still on the board" '["AB01"]' \
        "$(jq -c '[.stories[] | select(.id == "ST1") | .issues[] | select(.id != "AB02") | .id]' <<<"$ST_BODY")"
}

st_setup "$@"
fixture
st_planner "$PROJECT" "$OWN"
st_step 1 "the repository of the issues has no build of its own" step_1
st_step 2 "an issue moved between two planning states through the planner is saved" step_2
st_step 3 "an idea handed in through the planner gets the next free number" step_3
st_step 4 "a fresh clone holds the move, its stage record, the idea, both index rows and a passing check" step_4
st_step 5 "a head broken by hand is a fault on the board, and the rest of the board stands" step_5
st_skip 6 "porelo-buildtools and every repository build without the story and issue commands" \
    "their own builds show it"
st_finish
