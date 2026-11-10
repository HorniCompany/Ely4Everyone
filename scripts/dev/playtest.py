"""Manual in-game checks on Windows without disturbing whoever sits at the PC.

Game windows are moved off screen and never take focus (mcwin.ps1, run by Windows PowerShell 5.1:
its Add-Type knows System.Drawing, pwsh 7 does not). Sound is off in mod/run*/options.txt.
Screenshots are taken in game (F2) and land in <run dir>/screenshots.

    python scripts/dev/playtest.py check                    # games running? enough memory?
    python scripts/dev/playtest.py client 26.3 [--world "Новый мир" | --server]
    python scripts/dev/playtest.py server 26.3              # local offline server with RCON on 127.0.0.1
    python scripts/dev/playtest.py pirate ErickSkrauch      # 2nd client: no Ely login, nick of your choice
    python scripts/dev/playtest.py rcon "tp Cokeef 0 -60 0 facing 0 -59 3"
    python scripts/dev/playtest.py key f5 [--pid N]         # f1 f2 f3 f5 esc enter, or a virtual-key number
    python scripts/dev/playtest.py click 425 348 [--pid N]  # window pixels (854x480 by default)
    python scripts/dev/playtest.py shot [--pid N]           # F2, prints the new screenshot path
    python scripts/dev/playtest.py stop                     # clients, server, window guard, Gradle daemons

Everything long-running is started detached with output in build/playtest/*.log, so no command
here blocks on a pipe. Two clients plus a server need ~8 GB: `check` refuses below 12 GB of free
commit or while a known game runs, unless --force.
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import re
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / "build" / "playtest"
MCWIN = Path(__file__).with_name("mcwin.ps1")
SERVER_PORT = 25599
RCON_PORT = 25598
# Games that may be running on the owner's PC; extend when needed.
KNOWN_GAMES = re.compile(r"DyingLight|FortniteClient|Discovery|TheFinals|Phasmophobia|javaw", re.I)
KEYS = {"f1": 112, "f2": 113, "f3": 114, "f5": 116, "esc": 27, "enter": 13, "tab": 9}
CREATE_FLAGS = 0x00000008 | 0x00000200 if os.name == "nt" else 0  # DETACHED_PROCESS | NEW_PROCESS_GROUP


# ---------- helpers ----------

def ps(script: str) -> str:
    out = subprocess.run(["powershell.exe", "-NoProfile", "-Command", script], capture_output=True,
                         text=True, encoding="utf-8", errors="replace", stdin=subprocess.DEVNULL)
    return out.stdout.strip()


def mcwin(*args: str, pid: int | None = None) -> str:
    env = dict(os.environ, MCPID=str(pid or 0))
    out = subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(MCWIN), *args],
                         capture_output=True, text=True, encoding="utf-8", errors="replace",
                         stdin=subprocess.DEVNULL, env=env)
    return (out.stdout + out.stderr).strip()


def detach(cmd: list[str], log: Path, cwd: Path, env: dict | None = None) -> int:
    log.parent.mkdir(parents=True, exist_ok=True)
    handle = open(log, "w", encoding="utf-8")
    proc = subprocess.Popen(cmd, cwd=cwd, stdout=handle, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                            env=env, creationflags=CREATE_FLAGS)
    return proc.pid


def wait_for(path: Path, pattern: str, timeout: float, since: float = 0) -> str | None:
    deadline = time.time() + timeout
    regex = re.compile(pattern)
    while time.time() < deadline:
        if path.exists() and path.stat().st_mtime >= since:
            for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
                if regex.search(line):
                    return line
        time.sleep(1)
    return None


def gradle_homes() -> list[Path]:
    homes = [os.environ.get("GRADLE_USER_HOME"), "D:/Cache/gradle", str(Path.home() / ".gradle")]
    return [Path(h) for h in homes if h and Path(h, "caches").is_dir()]


def node_properties(node: str) -> dict[str, str]:
    path = ROOT / "mod" / "versions" / node / "gradle.properties"
    if not path.exists():
        sys.exit(f"no Stonecutter node {node} (mod/versions/{node})")
    props = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


def run_dir(node: str) -> Path:
    rel = node_properties(node).get("runDir", "../../run")
    return (ROOT / "mod" / "versions" / node / rel).resolve()


def java_for(node: str) -> str:
    version = node_properties(node).get("javaVersion", "21")
    for home in gradle_homes():
        for java in glob.glob(str(home / "jdks" / f"*{version}*" / "bin" / "java.exe")):
            return java
    for java in glob.glob(f"C:/Program Files/Eclipse Adoptium/jdk-{version}*/bin/java.exe"):
        return java
    sys.exit(f"no Java {version} found (Gradle downloads it on the first build of node {node})")


def server_jar(minecraft: str) -> Path:
    for home in gradle_homes():
        jar = home / "caches" / "fabric-loom" / minecraft / "minecraft-server.jar"
        if jar.exists():
            return jar
    sys.exit(f"no server jar for {minecraft} in the Loom cache: build node {minecraft} once")


def game_pids() -> list[dict]:
    raw = ps("Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
             "Select-Object ProcessId, CommandLine | ConvertTo-Json -Compress")
    items = json.loads(raw) if raw else []
    items = items if isinstance(items, list) else [items]
    return [p for p in items if p.get("CommandLine") and ("fabric.dli" in p["CommandLine"] or "minecraft-server.jar" in p["CommandLine"])]


def save_state(**values) -> None:
    STATE.mkdir(parents=True, exist_ok=True)
    path = STATE / "state.json"
    data = json.loads(path.read_text()) if path.exists() else {}
    data.update(values)
    path.write_text(json.dumps(data, indent=2))


def load_state() -> dict:
    path = STATE / "state.json"
    return json.loads(path.read_text()) if path.exists() else {}


# ---------- commands ----------

def cmd_check(args) -> bool:
    raw = ps("$o=Get-CimInstance Win32_OperatingSystem; "
             "'{0} {1}' -f $o.FreeVirtualMemory, $o.TotalVirtualMemorySize; "
             "Get-Process | Where-Object { $_.PagedMemorySize64 -gt 1GB } | ForEach-Object { $_.ProcessName + ' ' + [math]::Round($_.PagedMemorySize64/1GB,1) }")
    lines = raw.splitlines()
    free_kb, total_kb = (int(x) for x in lines[0].split())
    free_gb = free_kb / 1024 / 1024
    games = [l for l in lines[1:] if KNOWN_GAMES.search(l.split()[0])]
    print(f"free commit {free_gb:.1f} of {total_kb / 1024 / 1024:.1f} GB; big processes: {', '.join(lines[1:]) or 'none'}")
    ok = free_gb >= 12 and not games
    if not ok:
        print("NOT OK for client tests: " + (f"game running ({', '.join(games)})" if games else "less than 12 GB free commit"))
    return ok


def guard() -> None:
    running = ps("Get-CimInstance Win32_Process -Filter \"Name='powershell.exe'\" | "
                 "Where-Object { $_.CommandLine -match 'mcwin.ps1.*guard' } | Measure-Object | Select-Object -ExpandProperty Count")
    if running.strip() not in ("", "0"):
        return
    detach(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(MCWIN), "guard", "1800"],
           STATE / "guard.log", ROOT)


def cmd_client(args) -> None:
    if not args.force and not cmd_check(args):
        sys.exit("use --force to start anyway")
    node = args.node
    game_dir = run_dir(node)
    game_dir.mkdir(parents=True, exist_ok=True)
    if not (game_dir / "options.txt").exists() and (ROOT / "mod" / "run" / "options.txt").exists():
        shutil.copy(ROOT / "mod" / "run" / "options.txt", game_dir / "options.txt")  # sound off, no pause on focus loss
    guard()
    quick = []
    if args.server:
        quick = [f"--quickPlayMultiplayer 127.0.0.1:{SERVER_PORT}"]
    elif args.world:
        if not (game_dir / "saves" / args.world).exists():
            print(f"note: no world '{args.world}' in {game_dir}/saves; copy one there (a copy: newer versions upgrade it)")
        quick = [f'--quickPlaySingleplayer "{args.world}"']
    gradlew = str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    cmd = [gradlew, f":mod:{node}:runClient", "--console=plain"] + ([f"--args={quick[0]}"] if quick else [])
    started = time.time()
    detach(cmd, STATE / f"client-{node}.log", ROOT)
    latest = game_dir / "logs" / "latest.log"
    line = wait_for(latest, r"Ely4Everyone client initialized|Exception in|Mixin apply failed", 600, since=started)
    print(line or "client did not report in 10 minutes, see build/playtest/client-*.log")
    pid = next((p["ProcessId"] for p in game_pids() if "fabric.dli" in p["CommandLine"] and "playtest" not in p["CommandLine"]), None)
    save_state(client_pid=pid, client_node=node, client_dir=str(game_dir))
    print(f"client pid {pid}, game dir {game_dir}")


def cmd_server(args) -> None:
    props = node_properties(args.node)
    folder = STATE / f"server-{args.node}"
    folder.mkdir(parents=True, exist_ok=True)
    shutil.copy(server_jar(props["minecraftVersion"]), folder / "server.jar")
    password = secrets.token_hex(12)
    (folder / "eula.txt").write_text("eula=true\n")
    (folder / "server.properties").write_text("\n".join([
        "online-mode=false", f"server-port={SERVER_PORT}", "server-ip=127.0.0.1", "enforce-secure-profile=false",
        "view-distance=4", "simulation-distance=4", "spawn-protection=0", "level-type=minecraft\\:flat",
        "generate-structures=false", "max-players=4", "motd=ely4everyone playtest",
        "enable-rcon=true", f"rcon.port={RCON_PORT}", f"rcon.password={password}", "",
    ]))
    save_state(rcon_password=password, server_node=args.node)
    started = time.time()
    detach([java_for(args.node), "-Xmx1G", "-jar", "server.jar", "nogui"], folder / "server.log", folder)
    line = wait_for(folder / "server.log", r"RCON running|Exception|FAILED", 180, since=started)
    print(line or "server did not start, see " + str(folder / "server.log"))
    # Daytime, no mobs: otherwise slimes on the flat world kill the test players.
    for command in ("time set noon", "gamerule advance_time false", "gamerule doDaylightCycle false",
                    "gamerule spawn_mobs false", "gamerule doMobSpawning false", "difficulty peaceful"):
        rcon_send(command, quiet=True)


def rcon_send(command: str, quiet: bool = False) -> str:
    password = load_state().get("rcon_password") or sys.exit("no server started by playtest.py")
    with socket.create_connection(("127.0.0.1", RCON_PORT), timeout=5) as conn:
        def packet(request_id: int, kind: int, body: str) -> str:
            payload = body.encode() + b"\x00\x00"
            conn.sendall(struct.pack("<iii", len(payload) + 8, request_id, kind) + payload)
            size = struct.unpack("<i", conn.recv(4))[0]
            data = b""
            while len(data) < size:
                data += conn.recv(size - len(data))
            return data[8:-2].decode(errors="replace")
        packet(1, 3, password)
        reply = packet(2, 2, command)
    if not quiet:
        print(f"> {command}\n{reply}")
    return reply


def cmd_rcon(args) -> None:
    for command in args.commands:
        rcon_send(command)


def cmd_pirate(args) -> None:
    """Second client from the first one's command line, without an Ely login (own APPDATA)."""
    state = load_state()
    first = next((p for p in game_pids() if p["ProcessId"] == state.get("client_pid")), None)
    if not first:
        sys.exit("start the first client with `client <node> --server` before the pirate")
    match = re.match(r'\s*("[^"]+"|\S+)\s+(.*)$', first["CommandLine"], re.S)
    exe, rest = match.group(1).strip('"'), re.sub(r"\s--quickPlay\w+\s+(\"[^\"]*\"|\S+)", "", match.group(2))
    folder = STATE / f"pirate-{args.name}"
    (folder / "game").mkdir(parents=True, exist_ok=True)
    (folder / "appdata").mkdir(parents=True, exist_ok=True)
    options = Path(state["client_dir"]) / "options.txt"
    if options.exists():
        shutil.copy(options, folder / "game" / "options.txt")
    command_line = (f'"{exe}" {rest} --username {args.name} --gameDir "{folder / "game"}" '
                    f"--quickPlayMultiplayer 127.0.0.1:{SERVER_PORT}")
    env = dict(os.environ, APPDATA=str(folder / "appdata"))
    log = open(folder / "pirate.log", "w", encoding="utf-8")
    proc = subprocess.Popen(command_line, cwd=folder / "game", stdout=log, stderr=subprocess.STDOUT,
                            stdin=subprocess.DEVNULL, env=env, creationflags=CREATE_FLAGS)
    save_state(pirate_pid=proc.pid, pirate_dir=str(folder / "game"))
    print(f"pirate {args.name} pid {proc.pid}, game dir {folder / 'game'}")


def resolve_pid(args) -> int | None:
    if args.pid:
        return args.pid
    return load_state().get("client_pid")


def cmd_key(args) -> None:
    code = KEYS.get(args.key.lower()) or int(args.key)
    print(mcwin("key", str(code), pid=resolve_pid(args)))


def cmd_click(args) -> None:
    print(mcwin("click", str(args.x), str(args.y), pid=resolve_pid(args)))


def cmd_shot(args) -> None:
    pid = resolve_pid(args)
    state = load_state()
    folder = Path(state["pirate_dir"] if pid and pid == state.get("pirate_pid") else state.get("client_dir", ROOT / "mod" / "run")) / "screenshots"
    before = set(folder.glob("*.png")) if folder.exists() else set()
    mcwin("key", str(KEYS["f2"]), pid=pid)
    deadline = time.time() + 10
    while time.time() < deadline:
        new = (set(folder.glob("*.png")) if folder.exists() else set()) - before
        if new:
            print(max(new, key=lambda p: p.stat().st_mtime))
            return
        time.sleep(0.5)
    print("no new screenshot (window not rendering yet? press a key first)")


def cmd_stop(args) -> None:
    for p in game_pids():
        ps(f"Stop-Process -Id {p['ProcessId']} -Force")
        print(f"stopped {p['ProcessId']}")
    ps("Get-CimInstance Win32_Process -Filter \"Name='powershell.exe'\" | Where-Object { $_.CommandLine -match 'mcwin.ps1' } "
       "| ForEach-Object { Stop-Process -Id $_.ProcessId -Force }")
    subprocess.run([str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")), "--stop", "-q"], cwd=ROOT,
                   stdin=subprocess.DEVNULL, capture_output=True)
    print("guard and Gradle daemons stopped")


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("check").set_defaults(func=cmd_check)
    p = sub.add_parser("client"); p.add_argument("node"); p.add_argument("--world"); p.add_argument("--server", action="store_true")
    p.add_argument("--force", action="store_true"); p.set_defaults(func=cmd_client)
    p = sub.add_parser("server"); p.add_argument("node"); p.set_defaults(func=cmd_server)
    p = sub.add_parser("pirate"); p.add_argument("name"); p.set_defaults(func=cmd_pirate)
    p = sub.add_parser("rcon"); p.add_argument("commands", nargs="+"); p.set_defaults(func=cmd_rcon)
    p = sub.add_parser("key"); p.add_argument("key"); p.add_argument("--pid", type=int); p.set_defaults(func=cmd_key)
    p = sub.add_parser("click"); p.add_argument("x", type=int); p.add_argument("y", type=int); p.add_argument("--pid", type=int)
    p.set_defaults(func=cmd_click)
    p = sub.add_parser("shot"); p.add_argument("--pid", type=int); p.set_defaults(func=cmd_shot)
    sub.add_parser("stop").set_defaults(func=cmd_stop)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
