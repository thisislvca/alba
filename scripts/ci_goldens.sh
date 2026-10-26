#!/usr/bin/env bash
# Steps of the screenshot golden flow that both CI paths share (the pull request update and the manual record-screenshots
# run). Run from the repository root. See AGENTS.md "Screenshot goldens".
#
#   require-secrets          fail, naming them, unless ALBA_BOT_APP_ID and ALBA_BOT_PRIVATE_KEY are set (APP_ID, PRIVATE_KEY)
#   guard <pr head sha>      pull request runs: fail if the merge checkout is not that head, or if the head is already a
#                            bot golden update (the goldens still differ, so rendering is not deterministic)
#   record                   re-record every golden on this machine and stage the result; sets output `changed`
#   to-head                  pull request runs: carry the recorded goldens from the merge checkout onto the PR head
#   commit                   commit the staged goldens as the GitHub App (APP_SLUG, GH_TOKEN) and set output `sha`
#   comment <pr number>      create or update the PR's one golden comment (GH_TOKEN, REPO, COMMIT_SHA)
#   comment-failed <pr>      the same, saying the update failed (RUN_URL)
#   push <branch>            push HEAD to the branch with the App token (APP_TOKEN, REPO)
set -euo pipefail

goldens=app/src/test/screenshots
message='test: update screenshot goldens'
marker='<!-- alba-golden-update -->'

output() { if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "$1=$2" >> "$GITHUB_OUTPUT"; fi; }
summary() { if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then echo "$1" >> "$GITHUB_STEP_SUMMARY"; fi; }

upsert_comment() {
  local pr="$1" body="$2" id
  id="$(gh api "repos/$REPO/issues/$pr/comments" --paginate \
    --jq ".[] | select(.user.login == \"github-actions[bot]\" and (.body | contains(\"$marker\"))) | .id" | head -n 1)"
  if [ -n "$id" ]; then
    gh api --method PATCH "repos/$REPO/issues/comments/$id" -F "body=@$body" > /dev/null
  else
    gh api --method POST "repos/$REPO/issues/$pr/comments" -F "body=@$body" > /dev/null
  fi
}

case "${1:-}" in
  require-secrets)
    missing=()
    if [ -z "${APP_ID:-}" ]; then missing+=(ALBA_BOT_APP_ID); fi
    if [ -z "${PRIVATE_KEY:-}" ]; then missing+=(ALBA_BOT_PRIVATE_KEY); fi
    if [ "${#missing[@]}" -gt 0 ]; then
      echo "::error::Missing repository secret(s): ${missing[*]}. Updating screenshot goldens pushes them with the" \
        "repository's GitHub App so that the push starts CI. Add the App's ID as ALBA_BOT_APP_ID and its private key" \
        "(the whole .pem file) as ALBA_BOT_PRIVATE_KEY under Settings > Secrets and variables > Actions;" \
        "AGENTS.md ('Screenshot goldens') has the steps."
      exit 1
    fi
    ;;
  guard)
    head_sha="$2"
    if [ "$(git rev-parse 'HEAD^2')" != "$head_sha" ]; then
      echo "::error::The pull request moved on from $head_sha. A newer run owns the goldens; this one stops."
      exit 1
    fi
    author="$(git log -1 --format=%an "$head_sha")"
    if [ "$(git log -1 --format=%s "$head_sha")" = "$message" ] && [[ "$author" == *'[bot]' ]]; then
      echo "::error::The head commit is already a golden update by $author, yet the screenshots still differ from it." \
        "Rendering is not deterministic, so committing again would loop. Compare the 'screenshots' artifact of this run" \
        "with the last update, find what varies between runs, and fix that; do not record again."
      exit 1
    fi
    ;;
  record)
    rm -rf "$goldens"
    ./gradlew :app:testDebugUnitTest --tests '*Screenshots' -Proborazzi.test.record=true --no-daemon --console=plain
    git add -A "$goldens"
    if git diff --cached --quiet; then
      summary "The goldens are unchanged."
      output changed false
    else
      output changed true
    fi
    ;;
  to-head)
    head_sha="$(git rev-parse 'HEAD^2')"
    snapshot="$(mktemp -d)"
    cp -R "$goldens/." "$snapshot/"
    git checkout --quiet --force --detach "$head_sha"
    rm -rf "$goldens"
    mkdir -p "$goldens"
    cp -R "$snapshot/." "$goldens/"
    git add -A "$goldens"
    if git diff --cached --quiet; then
      echo "::error::Recorded goldens equal the pull request head's, yet this run's screenshots differed. The merge with" \
        "the base branch changed how a screen looks, or rendering is not deterministic. Update the branch and push again."
      exit 1
    fi
    output changed true
    ;;
  commit)
    bot="${APP_SLUG}[bot]"
    git config user.name "$bot"
    git config user.email "$(gh api "/users/$bot" --jq .id)+$bot@users.noreply.github.com"
    file="$(mktemp)"
    {
      echo "$message"
      echo
      git diff --cached --name-status --no-renames -- "$goldens" | node scripts/screenshot_changes.mjs trailer
    } > "$file"
    git commit --quiet -F "$file"
    output sha "$(git rev-parse HEAD)"
    ;;
  comment)
    body="$(mktemp)"
    git diff --name-status --no-renames HEAD~1 HEAD -- "$goldens" \
      | COMMIT_URL="https://github.com/$REPO/commit/$COMMIT_SHA" FILES_URL="https://github.com/$REPO/pull/$2/files" \
        node scripts/screenshot_changes.mjs comment > "$body"
    upsert_comment "$2" "$body"
    ;;
  comment-failed)
    body="$(mktemp)"
    printf '%s\n### Screenshot goldens not updated\n\nCI could not update the goldens, so the checks stay red. See [the run](%s).\n' \
      "$marker" "$RUN_URL" > "$body"
    upsert_comment "$2" "$body"
    ;;
  push)
    # The token is used only here, never stored in the checkout's git config.
    test "$2" != main
    git push "https://x-access-token:${APP_TOKEN}@github.com/${REPO}.git" "HEAD:refs/heads/$2"
    ;;
  *)
    echo "Usage: ci_goldens.sh require-secrets | guard | record | to-head | commit | comment | comment-failed | push" >&2
    exit 2
    ;;
esac
