# ST10 — Test machines by kind from the VM pool

**Priority:** ⏫ Highest · **State:** Review

**What a user gains.** A build leg that needs a test machine can ask for one by kind from the VM pool on the host's forge and gets an account that is reset afterward.

**The test.** porelo-frontend's leased leg asks for the kind `amd64` on Forgejo and gets an account of the pool, reset afterwards, and a rented machine on the central forge; no workflow names a provider.

**Issues:**

- [porelo-host#HO12](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho12) (Review)
- [porelo-buildtools#BT24](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt24) (Review)
- [porelo-buildtools#BT25](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt25) (Review)

## Notes

- The pool taken by the same call on Forgejo

## Data

```json
{
  "id": "ST10",
  "title": "Test machines by kind from the VM pool",
  "state": "Review",
  "gain": "A build leg that needs a test machine can ask for one by kind from the VM pool on the host's forge and gets an account that is reset afterward.",
  "test": "porelo-frontend's leased leg asks for the kind `amd64` on Forgejo and gets an account of the pool, reset afterwards, and a rented machine on the central forge; no workflow names a provider.",
  "priority": "Highest",
  "issues": [
    "porelo-host#HO12",
    "porelo-buildtools#BT24",
    "porelo-buildtools#BT25"
  ],
  "tested": null
}
```
