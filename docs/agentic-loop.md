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
- Claude implements on a `claude/issue-N-*` branch, runs `./gradlew test`, and a follow-up step in
  the workflow **auto-opens the PR** (titled from the commit, `Closes #N`). Just review + merge.
- **Iterate on a PR:** comment `@claude <follow-up>` on the PR.
- Claude follows `CLAUDE.md` (build/test commands, minimal-diff + trailer-free conventions).

Verified working (2026-06-04) on #20 → PR #21. Gotchas that bit during setup, now fixed in the
workflow: the action needs `id-token: write` even with an API key, and passing
`github_token: ${{ secrets.GITHUB_TOKEN }}` avoids having to install the Claude GitHub App.

## Testing the build
Semaphore builds the branch on push and publishes both flavors' debug APK as **workflow
artifacts** (`feltbok-<describe>.apk` for Norway, `feltbok-se-<describe>.apk` for Sweden — named
like the GitHub releases; see `.semaphore/semaphore.yml`). Open the PR's Semaphore check → the
workflow's **Artifacts** tab → download and sideload it. No need to pull the branch and build
locally — though `just install` still works if you'd rather.

## Not included (deliberately)
- **Screenshot/video proof** of the change running on an emulator — dropped for now (the
  emulator + UI-capture step is the fiddly part). Revisit later if the PRs need visual proof.
