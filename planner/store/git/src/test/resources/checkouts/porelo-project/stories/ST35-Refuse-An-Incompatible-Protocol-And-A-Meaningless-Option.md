# ST35 — Refuse an incompatible protocol and a meaningless option

**Priority:** 🔼 High · **State:** Analyzed

**What a user gains.** An agent whose protocol is incompatible with the core is refused, while meaningless options no longer get in the way of compatible ones.

**The test.** Install compatible and too-old core packages with the agent; only an incompatible protocol version is refused. Exercise the defined behavior of --now, or confirm its removal is explained; it must never be accepted without an effect.

**Issues:**

- [porelo#B230](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b230) (Analyzed)
- [porelo-claude-code#CC22](https://github.com/porelo-ai/porelo-claude-code/blob/main/issues/README.md#cc22) (Estimated)
- [porelo#B201](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b201) (Analyzed)

## Data

```json
{
  "id": "ST35",
  "title": "Refuse an incompatible protocol and a meaningless option",
  "state": "Analyzed",
  "gain": "An agent whose protocol is incompatible with the core is refused, while meaningless options no longer get in the way of compatible ones.",
  "test": "Install compatible and too-old core packages with the agent; only an incompatible protocol version is refused. Exercise the defined behavior of --now, or confirm its removal is explained; it must never be accepted without an effect.",
  "priority": "High",
  "issues": [
    "porelo#B230",
    "porelo-claude-code#CC22",
    "porelo#B201"
  ],
  "tested": null
}
```
