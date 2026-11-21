# GitHub store

The planning store in GitHub Issues: a project's stories and issues as issues and sub-issues, their states and sums
as fields of one GitHub project, read and written with the person's own GitHub access. It implements the
[store API](../api/README.md) and knows no Git; the project's own files reach it through the
[Git store](../git/README.md).

Where each part of a story and an issue stands in GitHub is in
[Stories and issues in GitHub Issues](../../../doc/github-issues.md).

A project's board is kept on disk as a [copy](../cache/README.md): a start serves it at once and the first poll reads
GitHub; a failed save or a refused access removes it.
