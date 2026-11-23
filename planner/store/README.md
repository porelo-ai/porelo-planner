# Store

This grouping module holds the planning store's [interface](api/README.md) and its implementations in
[Git](git/README.md), [GitHub Issues](github/README.md), and [local directories](local/README.md), and the [local copy](cache/README.md) of a
tracked project's board. Nothing outside the Git implementation knows Git.

The stores share [private file checks and project locks](files/README.md).
