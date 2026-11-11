# ST247 — One primary IPv4 and no IPv6

**Priority:** ⏫ Highest · **State:** Finished (2026-10-27)

**What a user gains.** A person renting an IPv4-only machine gets exactly one primary IPv4 address and no IPv6, as the provider inventory shows.

**The test.** Rent an IPv4-only leg machine and read the provider inventory; it shows exactly one primary IPv4 and no IPv6. Return the machine and confirm its resources are gone.

**Issues:**

- [porelo-buildtools#BT10](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt10) (Finished)

## Data

```json
{
  "id": "ST247",
  "title": "One primary IPv4 and no IPv6",
  "state": "Finished",
  "gain": "A person renting an IPv4-only machine gets exactly one primary IPv4 address and no IPv6, as the provider inventory shows.",
  "test": "Rent an IPv4-only leg machine and read the provider inventory; it shows exactly one primary IPv4 and no IPv6. Return the machine and confirm its resources are gone.",
  "priority": "Highest",
  "issues": [
    "porelo-buildtools#BT10"
  ],
  "tested": "2026-10-27"
}
```
