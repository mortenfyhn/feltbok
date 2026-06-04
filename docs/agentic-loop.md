# Issue → PR loop (Claude GitHub Action)

Label an issue **`claude`** (or comment **@claude** on an issue/PR) and the
[official Claude Code Action](https://github.com/anthropics/claude-code-action) implements the
change on a branch, runs `./gradlew test`, and opens a pull request for you to review and merge.
It's gated so it only runs deliberately (it spends API tokens). Workflow: `.github/workflows/claude.yml`.

GitHub Actions is used here only because this is GitHub issue/PR integration; release builds stay
on Semaphore (`docs/release.md`).

## One-time setup

1. **Add the API key secret** (only you can — it's your key):
   ```sh
   gh secret set ANTHROPIC_API_KEY --repo mortenfyhn/feltbok   # paste sk-ant-... when prompted
   ```
   (Or a Claude Code OAuth token via the `claude_code_oauth_token` input instead.)

2. **Repo settings already set** by API: workflow token = write, and "create/approve pull
   requests" enabled. If a PR ever fails to open, re-check
   Settings → Actions → General → Workflow permissions.

3. The **`claude` label** already exists.

## Use it
- **New work:** open an issue describing the change, then add the `claude` label.
- Claude implements on a `claude/issue-N-*` branch, runs `./gradlew test`, and comments on the
  issue with a one-click **"Create PR"** link (it pushes the branch but doesn't auto-open the PR).
  Click it (or `gh pr create --head <branch>`) to open the PR, then review + merge.
- **Iterate on a PR:** comment `@claude <follow-up>` on the PR.
- Claude follows `CLAUDE.md` (build/test commands, minimal-diff + trailer-free conventions).

Verified working (2026-06-04) on #20 → PR #21. Gotchas that bit during setup, now fixed in the
workflow: the action needs `id-token: write` even with an API key, and passing
`github_token: ${{ secrets.GITHUB_TOKEN }}` avoids having to install the Claude GitHub App.

## Not included (deliberately)
- **Screenshot/video proof** of the change running on an emulator — dropped for now (the
  emulator + UI-capture step is the fiddly part). Revisit later if the PRs need visual proof.
- The PR carries a debug APK only if you wire Semaphore to build the PR branch; for now, pull
  the branch and `just install` to test on a device.
