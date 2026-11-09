#!/usr/bin/env python3
"""
Generuje data/odcinki.json z OpenStreetMap.

1. Overpass: relacje enforcement=average_speed w Polsce (węzły from/to, maxspeed, length).
2. Współrzędne węzłów z API OSM.
3. Trasa from -> to z OSRM (router.project-osrm.org), uproszczona Douglas-Peucker 4 m.
4. Odrzuca odcinki, gdzie trasa nie zgadza się z tagiem length (>15%) lub omija bramki.

Użycie:  python3 tools/2026-11-08-buduj-odcinki.py  (pracuje w bieżącym katalogu, wynik: odcinki.json)
Dane: (c) OpenStreetMap contributors, ODbL.
"""
import json, math, time, urllib.request, urllib.parse, sys, os

OVERPASS = "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
UA = {"User-Agent": "odcinkowy-pomiar-builder"}

def pobierz_relacje():
    if os.path.exists("opp_rel.json"):
        return
    q = '[out:json][timeout:150];area["ISO3166-1"="PL"][admin_level=2]->.a;relation["enforcement"="average_speed"](area.a);out body;'
    data = urllib.parse.urlencode({"data": q}).encode()
    r = urllib.request.urlopen(urllib.request.Request(OVERPASS, data=data, headers=UA), timeout=200).read()
    open("opp_rel.json", "wb").write(r)

def pobierz_wezly():
    if os.path.exists("nodes.json"):
        return
    d = json.load(open("opp_rel.json"))
    ids = sorted({m["ref"] for e in d["elements"] for m in e["members"] if m["type"] == "node"})
    nodes = {}
    for i in range(0, len(ids), 150):
        url = "https://api.openstreetmap.org/api/0.6/nodes.json?nodes=" + ",".join(map(str, ids[i:i + 150]))
        r = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60))
        for n in r["elements"]:
            if "lat" in n:
                nodes[n["id"]] = [n["lat"], n["lon"]]
    json.dump(nodes, open("nodes.json", "w"))

pobierz_relacje()
pobierz_wezly()

d=json.load(open('opp_rel.json')); nodes={int(k):v for k,v in json.load(open('nodes.json')).items()}
def hav(a,b):
    R=6371000;p1,p2=math.radians(a[0]),math.radians(b[0]);dp=p2-p1;dl=math.radians(b[1]-a[1])
    h=math.sin(dp/2)**2+math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2;return 2*R*math.asin(math.sqrt(h))
def plen(p): return sum(hav(p[i],p[i+1]) for i in range(len(p)-1))
def dp(pts,eps):
    if len(pts)<3: return pts
    a,b=pts[0],pts[-1]
    # perpendicular distance using local projection
    lat0=math.radians(a[0]);
    def xy(p): return ((p[1]-a[1])*111320*math.cos(lat0),(p[0]-a[0])*110540)
    bx,by=xy(b);L=math.hypot(bx,by)
    best,idx=0,0
    for i in range(1,len(pts)-1):
        px,py=xy(pts[i])
        dd=abs(bx*py-by*px)/L if L>0 else math.hypot(px,py)
        if dd>best: best,idx=dd,i
    if best>eps: return dp(pts[:idx+1],eps)[:-1]+dp(pts[idx:],eps)
    return [a,b]
out=[];bad=[]
cache={}
try: cache=json.load(open('osrm_cache.json'))
except: pass
for e in d['elements']:
    t=e['tags'];f=[m['ref'] for m in e['members'] if m['role']=='from'][0];to=[m['ref'] for m in e['members'] if m['role']=='to'][0]
    A,B=nodes[f],nodes[to];key=f"{f}-{to}"
    if key not in cache:
        url=f"https://router.project-osrm.org/route/v1/driving/{A[1]},{A[0]};{B[1]},{B[0]}?overview=full&geometries=geojson&steps=true"
        for att in range(4):
            try:
                cache[key]=json.load(urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'odcinkowy-pomiar-builder'}),timeout=60));break
            except Exception as ex:
                print('retry',key,ex,file=sys.stderr);time.sleep(2**att)
        time.sleep(1.1)
        json.dump(cache,open('osrm_cache.json','w'))
    r=cache.get(key)
    if not r or r.get('code')!='Ok': bad.append((e['id'],'osrm'));continue
    rt=r['routes'][0]
    geom=[[round(c[1],6),round(c[0],6)] for c in rt['geometry']['coordinates']]
    names=[]
    for s in rt['legs'][0]['steps']:
        n=s.get('ref') or s.get('name')
        if n and n not in names: names.append(n)
    L=plen(geom);straight=hav(A,B)
    tl=t.get('length')
    off=None
    if tl:
        try:
            v=float(tl.replace(',','.').split()[0]); off=v*1000 if v<100 else v
        except: off=None
    snapA=hav(A,geom[0]);snapB=hav(B,geom[-1])
    flags=[]
    if snapA>40 or snapB>40: flags.append(f'snap {snapA:.0f}/{snapB:.0f}')
    if L>straight*1.6+300: flags.append(f'detour {L:.0f} vs {straight:.0f}')
    if L<500: flags.append(f'za krótki {L:.0f} m')
    if off and abs(L-off)/off>0.15: flags.append(f'len {L:.0f} vs tag {off:.0f}')
    if flags: bad.append((e['id'],t.get('ref'),flags));continue
    sp=dp(geom,4)
    ref=t.get('ref','')
    droga=names[0].split(';')[0] if names else ''
    out.append({"id":f"osm-{e['id']}","nazwa":f"{droga} {ref}".strip(),"droga":droga,"limit":int(t['maxspeed']),
                "dlugosc_m":int(round(off if off else L)),"trasa":sp})
json.dump({"wersja":1,"aktualizacja":time.strftime('%Y-%m-%d'),"zrodlo":"OpenStreetMap (relacje enforcement=average_speed, ODbL) + trasy OSRM","odcinki":out},
          open('odcinki.json','w'),ensure_ascii=False,separators=(',',':'))
print('ok',len(out),'bad',len(bad))
for b in bad: print(b)

