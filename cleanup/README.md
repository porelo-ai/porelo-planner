# Clean-up

The choice the CI's clean-up workflow makes of the planner's snapshot executables in Artifactory: which builds under
`porelo-files/porelo-planner/` go, read from that folder's listing, the project file and every named repository's
default branch. It reaches no network and deletes nothing itself; `.github/workflows/cleanup.yml` fetches the listings
and deletes what it names. It is never shipped and is no part of the [planner's executable](../planner/README.md).

- A snapshot build goes only when it is older than 30 days, not among the newest 10, and its version is written on no
  named repository's default branch. A release, a path outside `porelo-files/porelo-planner/` and a version not
  `x.y.z~snapshot.N` are never chosen, and a listing that cannot be read whole chooses nothing.
