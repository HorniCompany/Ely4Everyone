"""Release from your own PC: GitHub release + Modrinth, no hosted CI needed.

    python scripts/release.py              # build, then publish whatever is missing
    python scripts/release.py --dry-run    # show what would happen, publish nothing

The mod is built once per Minecraft version (the Stonecutter nodes in settings.gradle.kts,
settings in mod/versions/<minecraft>/gradle.properties). Each jar is
ely4everyone-mod-<version>+<minecraft>.jar.

Steps, each skipped when already done:
1. Checks that the working tree is clean and HEAD is pushed to origin/main.
2. GitHub: if release v<version> is missing, builds every node (tests run too) and creates one
   release with all mod jars. (The Velocity/Paper plugins live in their own repository since 0.3.)
3. Modrinth: one version per Minecraft version, number <version>+<minecraft>, game versions from
   modrinthGameVersions in the node properties (default: the node's own version). Each jar is
   **downloaded from the GitHub release**, so both places ship byte-identical files.
4. Modrinth page: the description is README.en.md with absolute links (see modrinth_body), updated
   when it differs.

Release notes live in changelog/<version>.md: the first line `# Title` is the release title,
the rest is the body used on GitHub and Modrinth.

The Modrinth token comes from the PAT_MODRINTH environment variable or, if unset, from a
PAT_MODRINTH= line in ~/.env (override with --env-file). It is never printed.
Needs: git, gh (logged in). Gradle fetches the JDKs it needs (21 and 25) by itself.
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


def read_nodes() -> list[str]:
    """Minecraft versions the mod is built for, from stonecutter { create(":mod") { versions(...) } }."""
    settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
    match = re.search(r"versions\(([^)]*)\)", settings)
    if not match:
        sys.exit("no Stonecutter versions(...) in settings.gradle.kts")
    nodes = re.findall(r'"([^"]+)"', match.group(1))
    if not nodes:
        sys.exit("empty Stonecutter versions(...) in settings.gradle.kts")
    return nodes


def read_node_properties(node: str) -> dict[str, str]:
    path = ROOT / "mod" / "versions" / node / "gradle.properties"
    properties = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip()
    if "minecraftVersion" not in properties:
        sys.exit(f"no minecraftVersion in {path.relative_to(ROOT)}")
    return properties


def game_versions(properties: dict[str, str]) -> list[str]:
    listed = properties.get("modrinthGameVersions", "")
    return [v.strip() for v in listed.split(",") if v.strip()] or [properties["minecraftVersion"]]


def read_changelog(version: str) -> tuple[str, str, str, str]:
    """(title, notes, English title, English notes). The notes are Russian first; an English part
    may follow after a line `---` and its own `# Title`. GitHub gets the whole file, Modrinth (an
    English page) only the English part, or everything when there is none."""
    path = ROOT / "changelog" / f"{version}.md"
    if not path.exists():
        sys.exit(f"write the release notes first: {path.relative_to(ROOT)} (first line '# Title')")
    lines = path.read_text(encoding="utf-8").splitlines()
    if not lines or not lines[0].startswith("# "):
        sys.exit(f"{path.relative_to(ROOT)} must start with '# Title'")
    title, notes = lines[0][2:].strip(), "\n".join(lines[1:]).strip() + "\n"
    english_title, english_notes = title, notes
    if "---" in lines:
        last_rule = len(lines) - 1 - lines[::-1].index("---")
        tail = lines[last_rule + 1:]
        while tail and not tail[0].strip():
            tail.pop(0)
        if tail and tail[0].startswith("# "):
            english_title = tail[0][2:].strip()
            english_notes = "\n".join(tail[1:]).strip() + "\n"
    return title, notes, english_title, english_notes


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


RAW = f"https://raw.githubusercontent.com/{REPO}/main/"
GITHUB = f"https://github.com/{REPO}/blob/main/"


def modrinth_body() -> str:
    """README.en.md as the Modrinth description: English only, absolute links, PNG banner, no GitHub alerts."""
    text = (ROOT / "README.en.md").read_text(encoding="utf-8")
    text = re.sub(r"\n[^\n]*README\.md[^\n]*\n", "\n", text)  # the "read in Russian" line
    text = text.replace("<br>\n</p>", "\n</p>")
    text = text.replace('src="img/vector/banner.svg"', f'src="{RAW}img/banner.png"')
    text = re.sub(r'src="(img/[^"]+)"', lambda m: f'src="{RAW}{m.group(1)}"', text)
    text = text.replace('href="LICENSE"', f'href="{GITHUB}LICENSE"').replace("](LICENSE)", f"]({GITHUB}LICENSE)")
    text = text.replace("> [!WARNING]\n", "> ⚠️ **Warning**\n>\n")
    return text.strip() + "\n"


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
    title, notes, english_title, english_notes = read_changelog(version)
    nodes = {node: read_node_properties(node) for node in read_nodes()}
    mod_jars = {node: f"ely4everyone-mod-{version}+{props['minecraftVersion']}.jar" for node, props in nodes.items()}
    print(f"release {tag} ({title}), Minecraft: {', '.join(nodes)}")

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
        run(str(ROOT / gradlew), *[f":mod:{node}:build" for node in nodes], "-q", capture=False)
        jars = [ROOT / "mod" / "versions" / node / "build" / "libs" / jar for node, jar in mod_jars.items()]
        missing = [str(jar) for jar in jars if not jar.exists()]
        if missing:
            sys.exit(f"build did not produce: {missing}")
        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8") as notes_file:
            notes_file.write(notes)
        run("gh", "release", "create", tag, "-R", REPO, "--target", head, "--title", f"{tag}: {title}",
            "--notes-file", notes_file.name, *map(str, jars))
        os.unlink(notes_file.name)
        print(f"GitHub: created https://github.com/{REPO}/releases/tag/{tag}")

    # 3. Modrinth: one version per Minecraft version, with the very jars from the GitHub release.
    project = modrinth("GET", f"/project/{MODRINTH_PROJECT}")
    published = {v["version_number"] for v in modrinth("GET", f"/project/{project['id']}/version")}
    token = None
    with tempfile.TemporaryDirectory() as download_dir:
        for node, props in nodes.items():
            number = f"{version}+{props['minecraftVersion']}"
            jar_name = mod_jars[node]
            if number in published:
                print(f"Modrinth: {number} already exists, skipping")
                continue
            data = {
                "project_id": project["id"],
                "version_number": number,
                "name": f"{version} for {props['minecraftVersion']}: {english_title}",
                "changelog": english_notes,
                "dependencies": [{"project_id": FABRIC_API_PROJECT_ID, "dependency_type": "required"}],
                "game_versions": game_versions(props),
                "version_type": version_type(version),
                "loaders": ["fabric"],
                "featured": True,
                "status": "listed",
                "file_parts": ["file"],
                "primary_file": "file",
            }
            if args.dry_run:
                shown = {k: v for k, v in data.items() if k != "changelog"}
                print(f"Modrinth: would upload {jar_name} from the GitHub release with {json.dumps(shown, ensure_ascii=False)}")
                continue
            token = token or modrinth_token(args.env_file)
            run("gh", "release", "download", tag, "-R", REPO, "--pattern", jar_name, "--dir", download_dir)
            body, content_type = multipart(data, jar_name, (Path(download_dir) / jar_name).read_bytes())
            created = modrinth("POST", "/version", token, body, content_type)
            print(f"Modrinth: published https://modrinth.com/mod/{MODRINTH_PROJECT}/version/{created['id']}")

    # 4. Modrinth page description from README.en.md.
    body = modrinth_body()
    if project.get("body", "").strip() == body.strip():
        print("Modrinth: description is up to date")
    elif args.dry_run:
        print("Modrinth: would update the description from README.en.md")
    else:
        token = token or modrinth_token(args.env_file)
        modrinth("PATCH", f"/project/{project['id']}", token, json.dumps({"body": body}).encode(), "application/json")
        print("Modrinth: description updated from README.en.md")


if __name__ == "__main__":
    main()
