# Store Cache

The local copy of a tracked project's board, so the planner shows a board at start without reading the whole project
from its tracker. The copy holds the planner's own terms - items, their issues, field values and links, and the
board's field and option ids - and knows no tracker: a tracked store, such as the one over
[GitHub Issues](../github/README.md), maps its tracker's answers to these records and back.

One file per project lies in a directory only the planner's account may read, named after the tracker's host, the
project's owner and its number. A copy is used only when its format, its project and the commit of the project's
files are the ones asked for; a copy that is broken, a link or another account's is named in the log and ignored,
never fatal, and the board is then read whole. A copy has no size bound: it is read through a stream, so a large
project's copy is never held whole beside its records, and the memory Fory may reserve while it reads one is bounded
by the file's own size, so a length a tampered file names is refused before anything is allocated for it.

The file is written in [Apache Fory](https://fory.apache.org/)'s binary format, its strings compressed, after a head
line that names the encoding and a digest of the records; only the copy's own records are read, each through its own
constructor. The native image initializes `ForyEncoding` when it is built, so Fory compiles its serializers then.
