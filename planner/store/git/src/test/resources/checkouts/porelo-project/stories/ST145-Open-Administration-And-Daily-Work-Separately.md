# ST145 — Open administration and daily work separately

**Priority:** 🔽 Low · **State:** Review

**What a user gains.** A person opens administration and daily work separately, as user, host-admin or access-admin, so each role sees only its own steps.

**The test.** Open the interface as user, host-admin and access-admin. Walk setup, admitting a person and daily work. Each role sees its intended functions; the stated SSH and VPN routes can actually be reached.

**Issues:**

- [porelo-project#PJ71](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj71) (Finished)

## Notes

- Separate Administrative Entry Points; Define Host Access Routes. Roles choose the offered functions and add no separate authorization check. Decide access routes before implementation; this parent also covers sprint 5.

## Data

```json
{
  "id": "ST145",
  "title": "Open administration and daily work separately",
  "state": "Review",
  "gain": "A person opens administration and daily work separately, as user, host-admin or access-admin, so each role sees only its own steps.",
  "test": "Open the interface as user, host-admin and access-admin. Walk setup, admitting a person and daily work. Each role sees its intended functions; the stated SSH and VPN routes can actually be reached.",
  "priority": "Low",
  "issues": [
    "porelo-project#PJ71"
  ],
  "tested": null
}
```
