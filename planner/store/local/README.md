# Local directory store

This module keeps a project's stories and issues as Markdown files in one directory on the person's
computer, outside every repository, for a project whose `project.yml` names `tracker: {kind: local}`.
By default the directory is `$XDG_DATA_HOME/porelo-planner/items/<project>/` (`%LOCALAPPDATA%` on
Windows), made at the project's first opening as the account's alone; a directory chosen elsewhere
must exist and is never made. It uses the [private file checks and locks](../files/README.md) and
the [GitHub store's item shape](../github/README.md) - fields, warnings, body sections and stage
entries; it calls no forge and commits nothing.

An item is one file `<repository>-<number>.md`: a JSON head of the form
[local-item-1](src/main/resources/local-item-1.json), the body's sections as a GitHub issue holds
them, and its stages in a section of their own, each the JSON of a stage comment with the instant it
ended, oldest first. Only regular files of that name are read, none through a link, none larger than
1 MiB, and none from a directory of more than 10 000 of them.
