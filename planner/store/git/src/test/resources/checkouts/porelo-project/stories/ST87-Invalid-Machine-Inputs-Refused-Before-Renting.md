# ST87 — Invalid machine inputs refused before renting

**Priority:** 🟰 Medium · **State:** Idea

**What a user gains.** Invalid machine inputs, such as an unknown provider or a missing field, are refused before anything is rented, with the same variable read on both forges.

**The test.** Read the same machine-provider variable on both forges. Request an unknown provider, omit a required parameter and supply an empty required secret; all are refused before renting. A complete request is accepted.

**Issues:**

- [porelo-buildtools#BT33](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt33) (Idea)

## Notes

- Define Machine Provider Descriptions; Validate Machine Provider Inputs. This parent also covers the next two sprints.

## Data

```json
{
  "id": "ST87",
  "title": "Invalid machine inputs refused before renting",
  "state": "Idea",
  "gain": "Invalid machine inputs, such as an unknown provider or a missing field, are refused before anything is rented, with the same variable read on both forges.",
  "test": "Read the same machine-provider variable on both forges. Request an unknown provider, omit a required parameter and supply an empty required secret; all are refused before renting. A complete request is accepted.",
  "priority": "Medium",
  "issues": [
    "porelo-buildtools#BT33"
  ],
  "tested": null
}
```
