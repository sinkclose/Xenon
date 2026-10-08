"""Find the previous published release in the current commit's history."""

import json
import os
import subprocess
from pathlib import Path


def previous_release_commit(releases, current="HEAD"):
    head = subprocess.check_output(["git", "rev-parse", current], text=True).strip()
    for release in sorted(releases, key=lambda item: item.get("published_at") or "", reverse=True):
        if release["draft"] or release["prerelease"]:
            continue
        if not any(asset["name"].endswith(".apk") for asset in release["assets"]):
            continue
        # Resolve the tag itself: target_commitish may be a moving branch name.
        ref = f"refs/tags/{release['tag_name']}^{{commit}}"
        resolved = subprocess.run(
            ["git", "rev-parse", "--verify", ref], text=True, capture_output=True,
        )
        if resolved.returncode:
            raise RuntimeError(f"Cannot resolve published release tag: {release['tag_name']}")
        commit = resolved.stdout.strip()
        if commit == head:
            continue  # Re-running a release must retain the original range.
        ancestor = subprocess.run(["git", "merge-base", "--is-ancestor", commit, head])
        if ancestor.returncode == 0:
            return commit
        if ancestor.returncode != 1:
            raise RuntimeError("Cannot check previous release ancestry")
    return ""  # First release includes the entire history.


def main():
    pages = json.loads(subprocess.check_output([
        "gh", "api", "--paginate", "--slurp",
        f"repos/{os.environ['GITHUB_REPOSITORY']}/releases?per_page=100",
    ], text=True))
    commit = previous_release_commit([release for page in pages for release in page])
    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
        output.write(f"previous_commit={commit}\n")
    print(f"Previous release commit: {commit or '(first release)'}")


if __name__ == "__main__":
    main()
