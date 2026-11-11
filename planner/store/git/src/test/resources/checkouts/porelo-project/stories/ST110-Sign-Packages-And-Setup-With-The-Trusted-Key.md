# ST110 — Sign packages and setup with the trusted key

**Priority:** 🟰 Medium · **State:** Estimated

**What a user gains.** A person can install Porelo and the Claude Code adapter from one joint published version and verify packages and setup with the trusted signing key.

**The test.** Install Porelo and the Claude Code adapter from their joint published version. Verify the RPM and versioned setup script. Send an unsigned package and one signed by another key through publication and installation; both paths refuse them.

**Issues:**

- [porelo#B99](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b99) (Estimated)
- [porelo-claude-code#CC23](https://github.com/porelo-ai/porelo-claude-code/blob/main/issues/README.md#cc23) (Estimated)

## Notes

- Confirm the other packages named by the signing parent before starting the joint change.

## Data

```json
{
  "id": "ST110",
  "title": "Sign packages and setup with the trusted key",
  "state": "Estimated",
  "gain": "A person can install Porelo and the Claude Code adapter from one joint published version and verify packages and setup with the trusted signing key.",
  "test": "Install Porelo and the Claude Code adapter from their joint published version. Verify the RPM and versioned setup script. Send an unsigned package and one signed by another key through publication and installation; both paths refuse them.",
  "priority": "Medium",
  "issues": [
    "porelo#B99",
    "porelo-claude-code#CC23"
  ],
  "tested": null
}
```
