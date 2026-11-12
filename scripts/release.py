"""Release from your own PC: GitHub release + Modrinth + CurseForge, no hosted CI needed.

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
5. CurseForge: one file per Minecraft version, the same jars from the GitHub release, tagged with
   the Minecraft version, Fabric, Client and the node's Java. A jar is skipped when its file name
   is in the project's public file list or in the local upload log (~/.cache/ely4everyone/
   curseforge-uploads.json): files waiting for moderation are not public yet. The CurseForge page
   description is edited by hand (Markdown mode), it is not synced from here.

Release notes live in changelog/<version>.md: the first line `# Title` is the release title,
the rest is the body used on GitHub; Modrinth and CurseForge get the English part.

Tokens come from the environment or, if unset, from a NAME= line in ~/.env (override with
--env-file): PAT_MODRINTH for Modrinth, CURSEFORGE_TOKEN (curseforge.com/account/api-tokens) for
CurseForge. They are never printed.
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
CURSEFORGE_PROJECT_ID = 1799445
CURSEFORGE_UPLOAD_API = "https://minecraft.curseforge.com/api"
CURSEFORGE_SITE_API = "https://www.curseforge.com/api/v1"  # public, no key: the project's file list
# Uploads still in moderation are missing from the public list; this log keeps reruns from duplicating them.
CURSEFORGE_LOG = Path.home() / ".cache" / "ely4everyone" / "curseforge-uploads.json"
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


def env_token(name: str, env_file: Path) -> str:
    token = os.environ.get(name, "").strip()
    if not token and env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            if line.startswith(f"{name}="):
                token = line.split("=", 1)[1].strip().strip('"').strip("'")
    if not token:
        sys.exit(f"no {name} in the environment or in {env_file}")
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


def curseforge(method: str, url: str, token: str | None = None, body: bytes | None = None,
               content_type: str | None = None):
    headers = {"User-Agent": USER_AGENT}
    if token:
        headers["X-Api-Token"] = token
    if content_type:
        headers["Content-Type"] = content_type
    request = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.loads(response.read() or b"null")
    except urllib.error.HTTPError as error:
        sys.exit(f"CurseForge {method} {url}: HTTP {error.code} {error.read().decode(errors='replace')[:500]}")


def curseforge_file_names() -> set[str]:
    """File names in the project's public file list (the site's own API, no key needed)."""
    names: set[str] = set()
    index = 0
    while True:
        page = curseforge("GET", f"{CURSEFORGE_SITE_API}/mods/{CURSEFORGE_PROJECT_ID}/files"
                                 f"?pageIndex={index}&pageSize=50&sort=dateCreated&sortDescending=true")
        files = page.get("data") or []
        names.update(f["fileName"] for f in files)
        if len(files) < 50:
            return names
        index += 1


def read_curseforge_log() -> dict[str, int]:
    try:
        return json.loads(CURSEFORGE_LOG.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def write_curseforge_log(log: dict[str, int]) -> None:
    CURSEFORGE_LOG.parent.mkdir(parents=True, exist_ok=True)
    CURSEFORGE_LOG.write_text(json.dumps(log, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def curseforge_game_version_ids(token: str, props: dict[str, str], catalogue: tuple[list, dict]) -> list[int]:
    """CurseForge tag ids for one node: its Minecraft versions, Fabric, Client and its Java."""
    versions, type_slugs = catalogue

    def find(name: str, slug_ok) -> int:
        for v in versions:
            if v["name"] == name and slug_ok(type_slugs.get(v["gameVersionTypeID"], "")):
                return v["id"]
        sys.exit(f"CurseForge has no game version tag '{name}' yet")

    minecraft = lambda slug: slug.startswith("minecraft-") and "snapshot" not in slug
    ids = [find(mc, minecraft) for mc in game_versions(props)]
    ids.append(find("Fabric", lambda slug: slug == "modloader"))
    ids.append(find("Client", lambda slug: slug == "environment"))
    ids.append(find(f"Java {props.get('javaVersion', '21')}", lambda slug: slug == "java"))
    return ids


def multipart(data: dict, file_name: str, file_bytes: bytes, data_field: str = "data") -> tuple[bytes, str]:
    boundary = uuid.uuid4().hex
    parts = [
        f'--{boundary}\r\nContent-Disposition: form-data; name="{data_field}"\r\n'
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
    text = text.replace('src="img/vector/banner-slim.svg"', f'src="{RAW}img/banner-slim.png"')
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
                # Modrinth caps version titles at 64 characters; the full title opens the changelog.
                "name": f"{version} for {props['minecraftVersion']}",
                "changelog": f"## {english_title}\n\n{english_notes}",
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
            token = token or env_token("PAT_MODRINTH", args.env_file)
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
        token = token or env_token("PAT_MODRINTH", args.env_file)
        modrinth("PATCH", f"/project/{project['id']}", token, json.dumps({"body": body}).encode(), "application/json")
        print("Modrinth: description updated from README.en.md")

    # 5. CurseForge: the same jars from the GitHub release, one file per Minecraft version.
    log = read_curseforge_log()
    uploaded = curseforge_file_names() | set(log)
    to_upload = {node: jar for node, jar in mod_jars.items() if jar not in uploaded}
    for node, jar in mod_jars.items():
        if node not in to_upload:
            print(f"CurseForge: {jar} already exists, skipping")
    if to_upload:
        cf_token = env_token("CURSEFORGE_TOKEN", args.env_file)
        type_slugs = {t["id"]: t["slug"] for t in curseforge("GET", f"{CURSEFORGE_UPLOAD_API}/game/version-types", cf_token)}
        catalogue = (curseforge("GET", f"{CURSEFORGE_UPLOAD_API}/game/versions", cf_token), type_slugs)
        with tempfile.TemporaryDirectory() as download_dir:
            for node, jar_name in to_upload.items():
                props = nodes[node]
                metadata = {
                    "displayName": f"{version} for {props['minecraftVersion']}",
                    "changelog": f"## {english_title}\n\n{english_notes}",
                    "changelogType": "markdown",
                    "gameVersions": curseforge_game_version_ids(cf_token, props, catalogue),
                    "releaseType": version_type(version),
                    "relations": {"projects": [{"slug": "fabric-api", "type": "requiredDependency"}]},
                }
                if args.dry_run:
                    shown = {k: v for k, v in metadata.items() if k != "changelog"}
                    print(f"CurseForge: would upload {jar_name} from the GitHub release with {json.dumps(shown)}")
                    continue
                run("gh", "release", "download", tag, "-R", REPO, "--pattern", jar_name, "--dir", download_dir)
                body, content_type = multipart(metadata, jar_name, (Path(download_dir) / jar_name).read_bytes(),
                                               data_field="metadata")
                created = curseforge("POST", f"{CURSEFORGE_UPLOAD_API}/projects/{CURSEFORGE_PROJECT_ID}/upload-file",
                                     cf_token, body, content_type)
                log[jar_name] = created["id"]
                write_curseforge_log(log)
                print(f"CurseForge: uploaded {jar_name} (file id {created['id']})")


if __name__ == "__main__":
    main()
