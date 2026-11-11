# ST172 — Apply the same checks to the other agents

**Priority:** 🔽 Low · **State:** Idea

**What a user gains.** OMP, Codex and Antigravity get the same checks, so an allowed server package is pinned and an altered one is refused.

**The test.** For OMP, Codex and Antigravity, start an allowed server package and then alter its contents. The allowed version works; a changed or unlisted version is refused.

**Issues:** none yet

## Notes

- Pin OMP Local MCP Servers; Pin Codex Local MCP Servers; Pin Antigravity Local MCP Servers. Confirm the final supported-agent list before splitting these parts.

## Data

```json
{
  "id": "ST172",
  "title": "Apply the same checks to the other agents",
  "state": "Idea",
  "gain": "OMP, Codex and Antigravity get the same checks, so an allowed server package is pinned and an altered one is refused.",
  "test": "For OMP, Codex and Antigravity, start an allowed server package and then alter its contents. The allowed version works; a changed or unlisted version is refused.",
  "priority": "Low",
  "issues": [],
  "tested": null
}
```
