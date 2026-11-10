"""Yarn -> Mojang name lookup for MC 1.21.11, joined on official (obfuscated) names.

usage:
  yarn2moj.py class net.minecraft.client.MinecraftClient       -> mojang class
  yarn2moj.py members net.minecraft.client.gui.screen.Screen   -> renamed members incl. superclasses
  yarn2moj.py scan file.kt [more.kt]                           -> imports + identifiers that need renaming
"""
import re, struct, sys, zipfile, pickle, os

L = "D:/Cache/gradle/caches/fabric-loom/1.21.11/"
YARN = L + "net.fabricmc.yarn.1_21_11.1.21.11+build.4-v2/mappings.tiny"
MOJ = L + "loom.mappings.1_21_11.layered+hash.2198-v2/mappings.tiny"
JAR = L + "minecraft-merged.jar"
CACHE = os.path.join(os.environ.get("TEMP", "/tmp"), "ely4everyone-y2m-1.21.11.pickle")


def load_tiny(path):
    classes, members = {}, {}  # official -> named ; (off_cls, kind, off_name, off_desc) -> named
    cur = None
    with open(path, encoding="utf-8") as f:
        next(f)
        for line in f:
            p = line.rstrip("\n").split("\t")
            if p[0] == "c":
                cur = p[1]
                classes[cur] = p[3]
            elif len(p) >= 2 and p[0] == "" and p[1] in ("m", "f"):
                members[(cur, p[1], p[3], p[2])] = p[5]
    return classes, members


def supers_from_jar():
    out = {}
    z = zipfile.ZipFile(JAR)
    for n in z.namelist():
        if not n.endswith(".class"):
            continue
        b = z.read(n)
        cnt = struct.unpack(">H", b[8:10])[0]
        i, cp = 10, [None] * cnt
        k = 1
        while k < cnt:
            t = b[i]
            if t == 1:
                ln = struct.unpack(">H", b[i + 1:i + 3])[0]
                cp[k] = b[i + 3:i + 3 + ln].decode("utf-8", "replace"); i += 3 + ln
            elif t in (7, 8, 16, 19, 20):
                cp[k] = ("ref", struct.unpack(">H", b[i + 1:i + 3])[0]); i += 3
            elif t == 15:
                i += 4
            elif t in (3, 4, 9, 10, 11, 12, 17, 18):
                i += 5
            elif t in (5, 6):
                i += 9; k += 1
            k += 1
        this_i, sup_i, icount = struct.unpack(">HHH", b[i + 2:i + 8])
        name = lambda idx: cp[cp[idx][1]] if idx else None
        ifs = [name(struct.unpack(">H", b[i + 8 + 2 * j:i + 10 + 2 * j])[0]) for j in range(icount)]
        out[name(this_i)] = [s for s in [name(sup_i)] + ifs if s]
    return out


def build():
    if os.path.exists(CACHE):
        return pickle.load(open(CACHE, "rb"))
    yc, ym = load_tiny(YARN)
    mc, mm = load_tiny(MOJ)
    sup = supers_from_jar()  # official names
    data = dict(yc=yc, ym=ym, mc=mc, mm=mm, sup=sup)
    pickle.dump(data, open(CACHE, "wb"))
    return data


D = build()
Y2OFF = {v: k for k, v in D["yc"].items()}


def off_of(yarn_cls):
    return Y2OFF.get(yarn_cls.replace(".", "/"))


def hierarchy(off):
    seen, stack = [], [off]
    while stack:
        c = stack.pop()
        if c in seen or c not in D["yc"]:
            continue
        seen.append(c)
        stack.extend(D["sup"].get(c, []))
    return seen


def members(yarn_cls):
    """yarn member name -> set of (mojang name, kind, owner yarn class)"""
    off = off_of(yarn_cls)
    res = {}
    for c in hierarchy(off):
        for (oc, kind, on, od), yname in D["ym"].items():
            if oc != c:
                continue
            mname = D["mm"].get((oc, kind, on, od), yname)
            res.setdefault(yname, set()).add((mname, kind, D["yc"][c].split("/")[-1]))
    return res


def cls(yarn):
    off = off_of(yarn)
    return D["mc"][off].replace("/", ".") if off else None


if __name__ == "__main__":
    mode, args = sys.argv[1], sys.argv[2:]
    if mode == "class":
        for a in args:
            print(a, "->", cls(a))
    elif mode == "members":
        for a in args:
            for y, s in sorted(members(a).items()):
                for m, k, owner in s:
                    if m != y:
                        print(f"{k} {y} -> {m}   ({owner})")
    elif mode == "scan":
        for path in args:
            src = open(path, encoding="utf-8").read()
            imps = re.findall(r"^import (net\.minecraft\.[\w.]+)", src, re.M)
            print(f"=== {path}")
            idents = set(re.findall(r"\b([a-zA-Z_]\w*)\b", src))
            pool = {}
            for imp in imps:
                print(f"  import {imp} -> {cls(imp)}")
                for y, s in members(imp).items():
                    pool.setdefault(y, set()).update(s)
            for y in sorted(idents):
                cands = {(m, k, o) for (m, k, o) in pool.get(y, ()) if m != y}
                # kotlin property syntax: getFoo()/isFoo() used as .foo
                for pre in ("get", "is"):
                    g = pre + y[:1].upper() + y[1:]
                    cands |= {(m, k, o + f" [as {g}]") for (m, k, o) in pool.get(g, ()) if m != g}
                if cands:
                    print(f"  {y}: " + "; ".join(sorted(f"{m} ({k},{o})" for m, k, o in cands)))
