#!/usr/bin/env bash
# Makes the GitHub project a carry-over writes into: an organization project (ProjectV2) with every field the planner
# writes and its options, and the label `story` in the repository that keeps the stories. The carry-over makes none
# of these and refuses a project that lacks one; it sets the Status options to the workflow's states itself.
#
#   github-project-setup.sh <organization> <title> <stories repository>   (the token on standard input)
#
# The token needs Projects (read and write) on the organization and Issues (read and write) on the stories
# repository. It reaches curl only through a configuration pipe, never a command line, a file or a line printed.
# Prints the project's number and address; the carry-over then runs with `--to <organization>/<number>`.
# Stops non-zero at the first refusal, naming what was made before it. It is not run again over a stop: a second run
# makes a second project, so a project left by a stop is deleted on GitHub first.
# shellcheck disable=SC2016 # GraphQL's variables stand in single quotes on purpose
set -uo pipefail

if [[ $# -ne 3 ]]; then
    echo "usage: $0 <organization> <title> <stories repository>   (the token on standard input)" >&2
    exit 2
fi
ORG=$1
TITLE=$2
STORIES=$3
if [[ ! $ORG =~ ^[A-Za-z0-9][A-Za-z0-9-]{0,38}$ || ! $STORIES =~ ^[A-Za-z0-9._-]{1,100}$ || $STORIES =~ ^\.+$ ]]; then
    echo "the organization or the repository is no GitHub name" >&2
    exit 2
fi
if [[ -z $TITLE || ${#TITLE} -gt 200 ]]; then
    echo "the title is empty or longer than 200 characters" >&2
    exit 2
fi
for tool in curl jq; do
    if ! command -v "$tool" >/dev/null; then
        echo "$tool is missing" >&2
        exit 2
    fi
done
IFS= read -r TOK || true
if [[ -z $TOK ]]; then
    echo "no token on standard input" >&2
    exit 2
fi

API=https://api.github.com
MADE=()
stop() {
    echo "stopped: $1" >&2
    if ((${#MADE[@]} > 0)); then
        printf 'made before the stop: %s\n' "${MADE[@]}" >&2
    fi
    exit 1
}

# call METHOD PATH [JSON]: sets CODE and BODY; the token goes to curl through its configuration pipe alone.
call() {
    local args=(-sS -w '\n%{http_code}' -X "$1" -H 'Accept: application/vnd.github+json'
        -H 'X-GitHub-Api-Version: 2022-11-28') answer
    [[ -z ${3:-} ]] || args+=(-H 'Content-Type: application/json' --data-binary @-)
    answer=$(printf '%s' "${3:-}" |
        curl "${args[@]}" --config <(printf 'header = "Authorization: Bearer %s"\n' "$TOK") "$API$2") ||
        answer=$'\n000'
    CODE=${answer##*$'\n'}
    BODY=${answer%$'\n'*}
}

# gql QUERY VARIABLES: sets BODY; fails on a refusal or any GraphQL error.
gql() {
    call POST /graphql "$(jq -nc --arg q "$1" --argjson v "$2" '{query: $q, variables: $v}')"
    [[ $CODE == 200 ]] && jq -e '(.errors // []) | length == 0' <<<"$BODY" >/dev/null
}

# GitHub's words of a refusal, at most one line of 200 characters.
said() {
    jq -r '[(.errors // [])[].message, .message // empty] | first // "no words"' <<<"$BODY" 2>/dev/null |
        head -n 1 | cut -c1-200
}

gql 'query($o: String!) { organization(login: $o) { id } }' "$(jq -nc --arg o "$ORG" '{o: $o}')" ||
    stop "the organization $ORG could not be read ($CODE: $(said))"
OWNER_ID=$(jq -r '.data.organization.id // ""' <<<"$BODY")
[[ -n $OWNER_ID ]] || stop "GitHub answered no id for the organization $ORG"

call GET "/repos/$ORG/$STORIES"
[[ $CODE == 200 ]] || stop "the repository $ORG/$STORIES could not be read ($CODE: $(said))"

gql 'mutation($o: ID!, $t: String!) { createProjectV2(input: {ownerId: $o, title: $t}) { projectV2 { id number url } } }' \
    "$(jq -nc --arg o "$OWNER_ID" --arg t "$TITLE" '{o: $o, t: $t}')" ||
    stop "the project could not be made ($CODE: $(said))"
PROJECT_ID=$(jq -er '.data.createProjectV2.projectV2.id // empty' <<<"$BODY") ||
    stop "GitHub answered the new project without its id"
NUMBER=$(jq -er '.data.createProjectV2.projectV2.number // empty' <<<"$BODY") ||
    stop "GitHub answered the new project without its number"
URL=$(jq -r '.data.createProjectV2.projectV2.url // ""' <<<"$BODY")
MADE+=("project $ORG/$NUMBER ($URL)")

options() { printf '%s\n' "$@" | jq -R . | jq -sc 'map({name: ., color: "GRAY", description: ""})'; }
field() { # NAME TYPE [OPTION...]
    local name=$1 type=$2 vars
    shift 2
    if [[ $type == SINGLE_SELECT ]]; then
        vars=$(jq -nc --arg p "$PROJECT_ID" --arg n "$name" --argjson o "$(options "$@")" \
            '{p: $p, n: $n, t: "SINGLE_SELECT", o: $o}')
    else
        vars=$(jq -nc --arg p "$PROJECT_ID" --arg n "$name" --arg t "$type" '{p: $p, n: $n, t: $t, o: null}')
    fi
    gql 'mutation($p: ID!, $n: String!, $t: ProjectV2CustomFieldType!, $o: [ProjectV2SingleSelectFieldOptionInput!]) { createProjectV2Field(input: {projectId: $p, name: $n, dataType: $t, singleSelectOptions: $o}) { projectV2Field { ... on ProjectV2FieldCommon { id } } } }' \
        "$vars" || stop "the field $name could not be made ($CODE: $(said))"
    MADE+=("field $name")
}
field Priority SINGLE_SELECT Highest High Medium Low Lowest
field Size SINGLE_SELECT XS S M L XL
field Risk SINGLE_SELECT harm ordinary small
field Worker SINGLE_SELECT Agent Manual
for name in Rank Estimate Minutes 'VM minutes' Tokens Stages; do
    field "$name" NUMBER
done

call POST "/repos/$ORG/$STORIES/labels" '{"name":"story","color":"5319e7","description":"A story the planner keeps"}'
if [[ $CODE == 201 ]]; then
    MADE+=("label story in $ORG/$STORIES")
elif [[ $CODE == 422 ]]; then
    call GET "/repos/$ORG/$STORIES/labels/story"
    [[ $CODE == 200 ]] || stop "the label story in $ORG/$STORIES could not be made or read ($CODE: $(said))"
    echo "the label story stands in $ORG/$STORIES already"
else
    stop "the label story could not be made in $ORG/$STORIES ($CODE: $(said))"
fi

echo "project $ORG/$NUMBER made: $URL"
echo "fields: Priority Size Risk Worker (single select), Rank Estimate Minutes 'VM minutes' Tokens Stages (numbers);"
echo "Status is GitHub's own, the carry-over sets its options"
echo "next: porelo-planner carry-over --project <the stories' checkout> --to $ORG/$NUMBER --dry-run"
