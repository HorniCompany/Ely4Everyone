"""Release from your own PC: GitHub release + Modrinth, no hosted CI needed.

    python scripts/release.py              # build, then publish whatever is missing
    python scripts/release.py --dry-run    # show what would happen, publish nothing

Steps, each skipped when already done:
1. Checks that the working tree is clean and HEAD is pushed to origin/main.
2. GitHub: if release v<version> is missing, builds the jars (tests run too) and creates the
   release with the mod and Velocity plugin jars.
3. Modrinth: if version <version> is missing, uploads the mod jar **downloaded from the GitHub
   release**, so both places ship byte-identical files.

Release notes live in changelog/<version>.md: the first line `# Title` is the release title,
the rest is the body used on GitHub and Modrinth.

The Modrinth token comes from the PAT_MODRINTH environment variable or, if unset, from a
PAT_MODRINTH= line in ~/.env (override with --env-file). It is never printed.
Needs: git, gh (logged in), JDK 21 for the build.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = "HorniCompany/Ely4Everyone"
MODRINTH_PROJECT = "ely4everyone"
FABRIC_API_PROJECT_ID = "P7dR8mSH"
MODRINTH_API = "https://api.modrinth.com/v2"
USER_AGENT = "HorniCompany/Ely4Everyone release script (github.com/HorniCompany/Ely4Everyone)"


def run(*cmd: str, capture: bool = True) -> str:
    result = subprocess.run(cmd, cwd=ROOT, text=True, encoding="utf-8",
                            capture_output=capture, check=False)
    if result.returncode != 0:
        detail = (result.stderr or result.stdout or "").strip() if capture else ""
        sys.exit(f"command failed: {' '.join(cmd)}\n{detail}")
    return (result.stdout or "").strip() if capture else ""


def git(*args: str) -> str:
    # The repo history once had a path with a drive letter; NTFS protection must stay off for it.
    return run("git", "-c", "core.protectNTFS=false", *args)


def read_version() -> str:
    match = re.search(r'^version\s*=\s*"([^"]+)"', (ROOT / "build.gradle.kts").read_text(encoding="utf-8"), re.M)
    if not match:
        sys.exit("no version in build.gradle.kts")
    return match.group(1)


def read_minecraft_version() -> str:
    match = re.search(r"^minecraftVersion=(\S+)", (ROOT / "gradle.properties").read_text(encoding="utf-8"), re.M)
    if not match:
        sys.exit("no minecraftVersion in gradle.properties")
    return match.group(1)


def read_changelog(version: str) -> tuple[str, str]:
    path = ROOT / "changelog" / f"{version}.md"
    if not path.exists():
        sys.exit(f"write the release notes first: {path.relative_to(ROOT)} (first line '# Title')")
    lines = path.read_text(encoding="utf-8").splitlines()
    if not lines or not lines[0].startswith("# "):
        sys.exit(f"{path.relative_to(ROOT)} must start with '# Title'")
    return lines[0][2:].strip(), "\n".join(lines[1:]).strip() + "\n"


def modrinth_token(env_file: Path) -> str:
    token = os.environ.get("PAT_MODRINTH", "").strip()
    if not token and env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            if line.startswith("PAT_MODRINTH="):
                token = line.split("=", 1)[1].strip().strip('"').strip("'")
    if not token:
        sys.exit(f"no PAT_MODRINTH in the environment or in {env_file}")
    return token


def modrinth(method: str, path: str, token: str | None = None, body: bytes | None = None,
             content_type: str | None = None):
    headers = {"User-Agent": USER_AGENT}
    if token:
        headers["Authorization"] = token
    if content_type:
        headers["Content-Type"] = content_type
    request = urllib.request.Request(MODRINTH_API + path, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.loads(response.read() or b"null")
    except urllib.error.HTTPError as error:
        sys.exit(f"Modrinth {method} {path}: HTTP {error.code} {error.read().decode(errors='replace')[:500]}")


def multipart(data: dict, file_name: str, file_bytes: bytes) -> tuple[bytes, str]:
    boundary = uuid.uuid4().hex
    parts = [
        f'--{boundary}\r\nContent-Disposition: form-data; name="data"\r\n'
        f"Content-Type: application/json\r\n\r\n{json.dumps(data, ensure_ascii=False)}\r\n".encode(),
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{file_name}"\r\n'
        f"Content-Type: application/java-archive\r\n\r\n".encode() + file_bytes + b"\r\n",
        f"--{boundary}--\r\n".encode(),
    ]
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def version_type(version: str) -> str:
    if "alpha" in version:
        return "alpha"
    if "beta" in version or "rc" in version:
        return "beta"
    return "release"


def main() -> None:
    # Windows consoles default to a legacy code page and mangle the Russian titles.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run", action="store_true", help="show the plan, publish nothing")
    parser.add_argument("--env-file", type=Path, default=Path.home() / ".env")
    args = parser.parse_args()

    version = read_version()
    tag = f"v{version}"
    minecraft = read_minecraft_version()
    title, notes = read_changelog(version)
    mod_jar_name = f"ely4everyone-mod-{version}.jar"
    print(f"release {tag} ({title}), Minecraft {minecraft}")

    # 1. Only publish what is in the public history.
    if git("status", "--porcelain", "--untracked-files=no"):
        sys.exit("working tree has uncommitted changes, commit them first")
    git("fetch", "-q", "origin", "main")
    head = git("rev-parse", "HEAD")
    if head != git("rev-parse", "origin/main"):
        sys.exit("HEAD is not origin/main: push first (or check out main)")

    # 2. GitHub release.
    existing = subprocess.run(["gh", "release", "view", tag, "-R", REPO, "--json", "tagName"],
                              cwd=ROOT, capture_output=True, text=True)
    if existing.returncode == 0:
        print(f"GitHub: {tag} already exists, skipping")
    elif args.dry_run:
        print(f"GitHub: would build and create {tag} at {head[:7]}")
    else:
        print("building (tests included)...")
        gradlew = "gradlew.bat" if os.name == "nt" else "./gradlew"
        run(str(ROOT / gradlew), ":mod:build", ":velocity-plugin:build", "-q", capture=False)
        jars = [ROOT / "mod/build/libs" / mod_jar_name,
                ROOT / "velocity-plugin/build/libs" / f"ely4everyone-velocity-plugin-{version}.jar"]
        missing = [str(jar) for jar in jars if not jar.exists()]
        if missing:
            sys.exit(f"build did not produce: {missing}")
        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8") as notes_file:
            notes_file.write(notes)
        run("gh", "release", "create", tag, "-R", REPO, "--target", head, "--title", f"{tag}: {title}",
            "--notes-file", notes_file.name, *map(str, jars))
        os.unlink(notes_file.name)
        print(f"GitHub: created https://github.com/{REPO}/releases/tag/{tag}")

    # 3. Modrinth, with the very jar from the GitHub release.
    project = modrinth("GET", f"/project/{MODRINTH_PROJECT}")
    published = {v["version_number"] for v in modrinth("GET", f"/project/{project['id']}/version")}
    if version in published:
        print(f"Modrinth: {version} already exists, skipping")
        return

    data = {
        "project_id": project["id"],
        "version_number": version,
        "name": f"{version}: {title}",
        "changelog": notes,
        "dependencies": [{"project_id": FABRIC_API_PROJECT_ID, "dependency_type": "required"}],
        "game_versions": [minecraft],
        "version_type": version_type(version),
        "loaders": ["fabric"],
        "featured": True,
        "status": "listed",
        "file_parts": ["file"],
        "primary_file": "file",
    }
    if args.dry_run:
        shown = {k: v for k, v in data.items() if k != "changelog"}
        print(f"Modrinth: would upload {mod_jar_name} from the GitHub release with {json.dumps(shown, ensure_ascii=False)}")
        return

    token = modrinth_token(args.env_file)
    with tempfile.TemporaryDirectory() as download_dir:
        run("gh", "release", "download", tag, "-R", REPO, "--pattern", mod_jar_name, "--dir", download_dir)
        jar_bytes = (Path(download_dir) / mod_jar_name).read_bytes()
    body, content_type = multipart(data, mod_jar_name, jar_bytes)
    created = modrinth("POST", "/version", token, body, content_type)
    print(f"Modrinth: published https://modrinth.com/mod/{MODRINTH_PROJECT}/version/{created['id']}")


if __name__ == "__main__":
    main()
