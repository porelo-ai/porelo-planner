# ST36 — Start local, organization and added providers

**Priority:** 🔼 High · **State:** Analyzed

**What a user gains.** A person can use local, organization HTTPS and added providers such as xAI, Zhipu and Z.AI, with supported ones starting and unsupported ones refused clearly.

**The test.** Use a loopback provider, an organization HTTPS provider, xAI, Zhipu and Z.AI. A supported dialect works; an unsupported dialect and HTTP outside loopback are refused before the start.

**Issues:**

- [porelo#P10](https://github.com/porelo-ai/porelo/blob/main/issues/providers/README.md#p10) (Analyzed)
- [porelo#B149](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b149) (Estimated)
- [porelo#P07](https://github.com/porelo-ai/porelo/blob/main/issues/providers/README.md#p07) (Estimated)
- [porelo#P08](https://github.com/porelo-ai/porelo/blob/main/issues/providers/README.md#p08) (Estimated)

## Data

```json
{
  "id": "ST36",
  "title": "Start local, organization and added providers",
  "state": "Analyzed",
  "gain": "A person can use local, organization HTTPS and added providers such as xAI, Zhipu and Z.AI, with supported ones starting and unsupported ones refused clearly.",
  "test": "Use a loopback provider, an organization HTTPS provider, xAI, Zhipu and Z.AI. A supported dialect works; an unsupported dialect and HTTP outside loopback are refused before the start.",
  "priority": "High",
  "issues": [
    "porelo#P10",
    "porelo#B149",
    "porelo#P07",
    "porelo#P08"
  ],
  "tested": null
}
```
