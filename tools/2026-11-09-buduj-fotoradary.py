#!/usr/bin/env python3
"""
Generuje data/fotoradary.json z OpenStreetMap: stałe fotoradary (highway=speed_camera) w Polsce.

1. Overpass: węzły highway=speed_camera + relacje type=enforcement, enforcement=maxspeed.
2. Kierunek jazdy: tag direction + 180° (tag mówi, gdzie patrzy kamera), a gdy brak – z relacji: azymut węzła "from" -> urządzenie.
   Brak kierunku albo direction=both -> ostrzegamy w obu kierunkach (-1).
3. Pomija fotoradary przy bramkach OPP (do 150 m od początku/końca odcinka z data/odcinki.json) –
   tam ostrzega już odcinkowy pomiar.

Użycie:  python tools/2026-11-09-buduj-fotoradary.py   (z katalogu repo; cache w bieżącym katalogu tools/.cache)
Dane: (c) OpenStreetMap contributors, ODbL.
"""
import json, math, os, time, urllib.parse, urllib.request

OVERPASS = "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
UA = {"User-Agent": "odcinkowy-pomiar-builder"}
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(REPO, "tools", ".cache")
os.makedirs(CACHE, exist_ok=True)

KOMPAS = {"N": 0, "NNE": 22.5, "NE": 45, "ENE": 67.5, "E": 90, "ESE": 112.5, "SE": 135, "SSE": 157.5,
          "S": 180, "SSW": 202.5, "SW": 225, "WSW": 247.5, "W": 270, "WNW": 292.5, "NW": 315, "NNW": 337.5}


def overpass():
    f = os.path.join(CACHE, "foto_raw.json")
    if not os.path.exists(f):
        q = ('[out:json][timeout:150];area["ISO3166-1"="PL"][admin_level=2]->.a;'
             '(node["highway"="speed_camera"](area.a);'
             'relation["type"="enforcement"]["enforcement"="maxspeed"](area.a););out body;')
        r = urllib.request.urlopen(urllib.request.Request(
            OVERPASS, data=urllib.parse.urlencode({"data": q}).encode(), headers=UA), timeout=200).read()
        open(f, "wb").write(r)
    return json.load(open(f, encoding="utf-8"))


def wezly(ids):
    f = os.path.join(CACHE, "foto_nodes.json")
    nodes = json.load(open(f)) if os.path.exists(f) else {}
    brak = sorted(i for i in ids if str(i) not in nodes)
    for i in range(0, len(brak), 150):
        url = "https://api.openstreetmap.org/api/0.6/nodes.json?nodes=" + ",".join(map(str, brak[i:i + 150]))
        r = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60))
        for n in r["elements"]:
            if "lat" in n:
                nodes[str(n["id"])] = [n["lat"], n["lon"]]
        time.sleep(1)
    json.dump(nodes, open(f, "w"))
    return {int(k): v for k, v in nodes.items()}


def hav(a, b):
    R = 6371000
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    h = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(b[1] - a[1]) / 2) ** 2
    return 2 * R * math.asin(math.sqrt(h))


def azymut(a, b):
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dl = math.radians(b[1] - a[1])
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def kierunek_z_tagu(v):
    if not v:
        return None
    v = v.strip().upper()
    if v in KOMPAS:
        return KOMPAS[v]
    try:
        return float(v) % 360
    except ValueError:
        return None  # "both", "forward" itp.


def main():
    d = overpass()
    kamery = {e["id"]: e for e in d["elements"] if e["type"] == "node"}
    relacje = [e for e in d["elements"] if e["type"] == "relation"]
    # urządzenie -> lista węzłów "from"
    od = {}
    for r in relacje:
        dev = [m["ref"] for m in r["members"] if m["role"] == "device" and m["type"] == "node"]
        frm = [m["ref"] for m in r["members"] if m["role"] == "from" and m["type"] == "node"]
        for dv in dev:
            od.setdefault(dv, []).extend(frm)
    coords = wezly({f for l in od.values() for f in l})

    opp = json.load(open(os.path.join(REPO, "data", "odcinki.json"), encoding="utf-8"))["odcinki"]
    bramki = [o["trasa"][0] for o in opp] + [o["trasa"][-1] for o in opp]

    out, pominiete_opp, kier_tag, kier_rel = [], 0, 0, 0
    for nid, n in kamery.items():
        t = n.get("tags", {})
        p = [n["lat"], n["lon"]]
        if any(hav(p, b) < 150 for b in bramki):
            pominiete_opp += 1
            continue
        # Tag direction = w którą stronę patrzy kamera (na nadjeżdżających) – kierunek jazdy jest przeciwny.
        # Sprawdzone na relacjach enforcement: 37 z 39 zgodnych po odwróceniu o 180°.
        kier = kierunek_z_tagu(t.get("direction"))
        if kier is not None:
            kier = (kier + 180) % 360
            kier_tag += 1
        elif t.get("direction", "").lower() != "both":
            kier_rel_lista = [azymut(coords[f], p) for f in od.get(nid, []) if f in coords and hav(coords[f], p) > 5]
            if len(kier_rel_lista) == 1:
                kier = kier_rel_lista[0]
                kier_rel += 1
        try:
            limit = int(t.get("maxspeed", "0").split()[0])
        except ValueError:
            limit = 0
        out.append([round(p[0], 6), round(p[1], 6), limit, -1 if kier is None else round(kier)])

    out.sort()
    json.dump({"wersja": 1, "aktualizacja": time.strftime("%Y-%m-%d"),
               "zrodlo": "OpenStreetMap (highway=speed_camera, ODbL)",
               "opis": "[lat, lon, limit (0 = brak), kierunek jazdy w stopniach (-1 = oba)]",
               "fotoradary": out},
              open(os.path.join(REPO, "data", "fotoradary.json"), "w", encoding="utf-8", newline="\n"),
              ensure_ascii=False, separators=(",", ":"))
    print(f"fotoradarów: {len(out)}, pominięte przy bramkach OPP: {pominiete_opp}, "
          f"kierunek z tagu: {kier_tag}, z relacji: {kier_rel}, bez kierunku: {sum(1 for o in out if o[3] < 0)}")


if __name__ == "__main__":
    main()
