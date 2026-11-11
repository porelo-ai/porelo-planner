# ST285 — Host cards by name, a forge on its own port, a fast build

**Priority:** ⏫ Highest · **State:** Finished (2026-11-03)

**What a user gains.** A person can use host cards with a DNS name or an IPv6 address, run a forge on its own port, and get a fast build.

**The test.** A host card with a DNS name or an IPv6 address is read and connected; a vault token is lent to a forge on a port other than 443; the porelo Build's verify ends in about 12 minutes, shown by a real Build and not by a step of the walk.

**Issues:**

- [porelo-frontend#F291](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f291) (Finished)
- [porelo#B373](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b373) (Finished)
- [porelo#B383](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b383) (Finished)

## Data

```json
{
  "id": "ST285",
  "title": "Host cards by name, a forge on its own port, a fast build",
  "state": "Finished",
  "gain": "A person can use host cards with a DNS name or an IPv6 address, run a forge on its own port, and get a fast build.",
  "test": "A host card with a DNS name or an IPv6 address is read and connected; a vault token is lent to a forge on a port other than 443; the porelo Build's verify ends in about 12 minutes, shown by a real Build and not by a step of the walk.",
  "priority": "Highest",
  "issues": [
    "porelo-frontend#F291",
    "porelo#B373",
    "porelo#B383"
  ],
  "tested": "2026-11-03"
}
```
