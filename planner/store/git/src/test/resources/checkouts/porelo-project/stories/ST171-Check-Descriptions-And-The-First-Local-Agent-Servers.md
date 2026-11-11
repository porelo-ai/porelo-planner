# ST171 — Check descriptions and the first local agent servers

**Priority:** 🔽 Low · **State:** Idea

**What a user gains.** Tool descriptions are checked against rules, and Claude Code's and Pi's local MCP servers are pinned so a changed one is caught.

**The test.** Trigger a description rule with a known counterexample. Start pinned Claude Code and Pi local servers, then change each package or digest; the changed start fails. The rule makes no claim of unlimited protection against prompt injection.

**Issues:** none yet

## Notes

- Check MCP Tool Descriptions; Pin Claude Code Local MCP Servers; Pin Pi Local MCP Servers. Continue sprint 2.

## Data

```json
{
  "id": "ST171",
  "title": "Check descriptions and the first local agent servers",
  "state": "Idea",
  "gain": "Tool descriptions are checked against rules, and Claude Code's and Pi's local MCP servers are pinned so a changed one is caught.",
  "test": "Trigger a description rule with a known counterexample. Start pinned Claude Code and Pi local servers, then change each package or digest; the changed start fails. The rule makes no claim of unlimited protection against prompt injection.",
  "priority": "Low",
  "issues": [],
  "tested": null
}
```
