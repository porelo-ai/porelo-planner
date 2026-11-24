# Private files

This module checks private directories and files and holds file-backed project locks for the
[Git](../git/README.md) and local directory stores. It depends on neither Git nor a tracker client;
POSIX modes and Windows owners and ACLs enforce the same account boundary.

It also formats durations in seconds for store and application diagnostics, keeping their fractions.
