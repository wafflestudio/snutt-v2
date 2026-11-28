#!/usr/bin/env python3
import csv
import heapq
import json
import math
import sys
from collections import Counter, defaultdict

KIND_NAMES = ["PATH", "STEPS", "ROAD", "CONNECTOR", "INDOOR"]
K_STEPS, K_CONNECTOR, K_INDOOR = 1, 3, 4
PROFILES = ("fast", "gentle", "nostairs", "rain")
HEADWAYS = {
    "교내 순환셔틀": (5, 5, "학기 중 평일 08~19시 5분 (서울대 교내순환 셔틀버스 안내)", False),
    "관악02-1": (6, 6, "6분", False),
    "관악02-2": (20, 20, "20분", False),
}
CAMPUS_BUFFER_M = 30
BUILDING_BUFFER_M = 50
ENTRANCE_MATCH_M = 0.5
INSERT_CHUNK = 500


def read_inputs(h):
    rows = lambda path: list(csv.DictReader(open(f"{h}/{path}", encoding="utf-8")))
    osm = json.load(open(f"{h}/raw/osm_map.json", encoding="utf-8"))["elements"]
    return {
        "nodes": rows("data/nodes.csv"),
        "edges": rows("data/edges.csv"),
        "buildings": json.load(open(f"{h}/data/buildings.json", encoding="utf-8")),
        "polygons": json.load(open(f"{h}/data/building_polygons.geojson", encoding="utf-8"))["features"],
        "stops": rows("data/transit_stops.csv"),
        "segments": rows("data/transit_segments.csv"),
        "transit": json.load(open(f"{h}/prototype/transit.json", encoding="utf-8")),
        "osm": osm,
        "vectors": json.load(open(f"{h}/code/test_vectors.json", encoding="utf-8")),
    }


def dist_m(la1, lo1, la2, lo2):
    r = math.pi / 180
    x = (lo2 - lo1) * r * math.cos((la1 + la2) / 2 * r)
    y = (la2 - la1) * r
    return math.sqrt(x * x + y * y) * 6371000


def seg_dist_m(p, a, b):
    k = math.cos(math.radians(p[0])) * 111320
    s = 111320
    ax, ay, bx, by, px, py = a[1] * k, a[0] * s, b[1] * k, b[0] * s, p[1] * k, p[0] * s
    dx, dy = bx - ax, by - ay
    length2 = dx * dx + dy * dy
    u = 0 if length2 == 0 else max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / length2))
    return math.hypot(px - ax - u * dx, py - ay - u * dy)


def inside(p, ring):
    c = False
    for i in range(len(ring) - 1):
        (y1, x1), (y2, x2) = ring[i], ring[i + 1]
        if (y1 > p[0]) != (y2 > p[0]) and p[1] < (x2 - x1) * (p[0] - y1) / (y2 - y1) + x1:
            c = not c
    return c


def near_ring(p, ring, limit_m, bbox):
    if not (bbox[0] <= p[0] <= bbox[2] and bbox[1] <= p[1] <= bbox[3]):
        return False
    return inside(p, ring) or any(seg_dist_m(p, ring[i], ring[i + 1]) <= limit_m for i in range(len(ring) - 1))


def ring_bbox(ring, margin_m):
    dla = margin_m / 111320
    dlo = margin_m / (111320 * math.cos(math.radians(ring[0][0])))
    return (min(p[0] for p in ring) - dla, min(p[1] for p in ring) - dlo,
            max(p[0] for p in ring) + dla, max(p[1] for p in ring) + dlo)


class Graph:
    def __init__(self, lat, lon, edges):
        self.lat, self.lon = lat, lon
        self.ea, self.eb, self.kind, self.covered, self.e0, self.e1, self.length = [], [], [], [], [], [], []
        self.adj = [[] for _ in lat]
        for i, (a, b, kind, covered, e0, e1) in enumerate(edges):
            length = dist_m(lat[a], lon[a], lat[b], lon[b]) * (1.3 if kind == K_INDOOR else 1)
            for col, v in ((self.ea, a), (self.eb, b), (self.kind, kind), (self.covered, covered),
                           (self.e0, e0), (self.e1, e1), (self.length, length)):
                col.append(v)
            self.adj[a].append(i)
            self.adj[b].append(i)

    def climb(self, i, forward):
        return self.e1[i] - self.e0[i] if forward else self.e0[i] - self.e1[i]

    def edge_time(self, i, forward):
        length, dh, kind = self.length[i], self.climb(i, forward), self.kind[i]
        if kind in (K_STEPS, K_INDOOR):
            return max(length / (1.0 if kind == K_STEPS else 1.2), dh / 0.3 if dh > 0 else -dh / 0.4)
        if length < 0.5:
            return length / 1.4
        slope = max(-0.5, min(0.5, dh / length))
        return length / (6 * math.exp(-3.5 * abs(slope + 0.05)) / 3.6)

    def cost(self, profile, i, forward):
        t = self.edge_time(i, forward)
        kind = self.kind[i]
        if profile == "fast":
            return t
        if profile == "gentle":
            return t * (1.3 if kind == K_STEPS else 1) + max(self.climb(i, forward), 0) * 10
        if profile == "nostairs":
            return math.inf if kind in (K_STEPS, K_INDOOR) else t
        if profile == "rain":
            return t * (0.6 if self.covered[i] else 2.2 if kind == K_STEPS else 1.5)
        raise ValueError(profile)

    def dijkstra(self, sources, profile, targets=None):
        dist = [math.inf] * len(self.lat)
        prev = [-1] * len(self.lat)
        heap = []
        for s in sources:
            dist[s] = 0.0
            heapq.heappush(heap, (0.0, s))
        while heap:
            c, u = heapq.heappop(heap)
            if c > dist[u]:
                continue
            if targets is not None and u in targets:
                return dist, prev, u
            for i in self.adj[u]:
                forward = self.ea[i] == u
                v = self.eb[i] if forward else self.ea[i]
                w = self.cost(profile, i, forward)
                if c + w < dist[v]:
                    dist[v] = c + w
                    prev[v] = i
                    heapq.heappush(heap, (c + w, v))
        return dist, prev, None

    def path_edges(self, prev, end):
        steps = []
        u = end
        while prev[u] >= 0:
            i = prev[u]
            forward = self.eb[i] == u
            steps.append((i, forward))
            u = self.ea[i] if forward else self.eb[i]
        return steps[::-1]

    def route_time(self, sources, targets, profile):
        _, prev, end = self.dijkstra(sources, profile, set(targets))
        if end is None:
            return None
        return sum(self.edge_time(i, f) for i, f in self.path_edges(prev, end))


def campus_rings(osm):
    nodes = {x["id"]: x for x in osm if x["type"] == "node"}
    ways = {x["id"]: x for x in osm if x["type"] == "way"}
    campus = next(r for r in osm if r["type"] == "relation" and r.get("tags", {}).get("amenity") == "university")
    segs = [list(ways[m["ref"]]["nodes"]) for m in campus["members"] if m["role"] == "outer" and m["ref"] in ways]
    rings = []
    while segs:
        ring = segs.pop(0)
        joined = True
        while ring[0] != ring[-1] and joined:
            joined = False
            for s in segs:
                if s[0] == ring[-1]:
                    ring += s[1:]
                elif s[-1] == ring[-1]:
                    ring += s[::-1][1:]
                elif s[-1] == ring[0]:
                    ring = s + ring[1:]
                elif s[0] == ring[0]:
                    ring = s[::-1] + ring[1:]
                else:
                    continue
                segs.remove(s)
                joined = True
                break
        if ring[0] != ring[-1]:
            raise SystemExit(f"캠퍼스 경계가 닫히지 않는다: {campus['tags'].get('name')}")
        rings.append([(nodes[n]["lat"], nodes[n]["lon"]) for n in ring])
    return rings


def osm_structures(osm):
    nodes = {x["id"]: x for x in osm if x["type"] == "node"}
    ways = {x["id"]: x for x in osm if x["type"] == "way"}
    out = []
    for e in osm:
        tags = e.get("tags", {})
        if "building" not in tags:
            continue
        if e["type"] == "way":
            rings = [e["nodes"]]
        elif e["type"] == "relation":
            rings = [ways[m["ref"]]["nodes"] for m in e["members"]
                     if m["type"] == "way" and m.get("role") in ("outer", "") and m["ref"] in ways]
        else:
            rings = []
        for r in rings:
            if len(r) < 4 or r[0] != r[-1] or any(n not in nodes for n in r):
                continue
            out.append({"ring": [(nodes[n]["lat"], nodes[n]["lon"]) for n in r],
                        "entrances": [nodes[n] for n in dict.fromkeys(r) if "entrance" in nodes[n].get("tags", {})],
                        "tags": tags})
    return out


WALKABLE_HIGHWAYS = {"footway", "path", "steps", "pedestrian", "service", "residential", "living_street",
                     "unclassified", "track", "cycleway", "secondary", "secondary_link", "primary", "primary_link"}


def walkable_osm_nodes(osm):
    nodes = {x["id"] for x in osm if x["type"] == "node"}
    out = set()
    for w in osm:
        t = w.get("tags", {})
        if w["type"] != "way" or t.get("highway") not in WALKABLE_HIGHWAYS or any(n not in nodes for n in w["nodes"]):
            continue
        if t.get("foot") == "no" or t.get("access") in ("no", "private"):
            continue
        out.update(w["nodes"])
    return out


def floors_of(tags):
    v = tags.get("building:levels", "")
    return int(v) if v.isdigit() else None


def wheelchair_of(tags):
    return {"yes": True, "no": False}.get(tags.get("wheelchair"))


def prune(g, src, rings, polygons, buildings):
    pts = list(zip(g.lat, g.lon))
    campus = [(r, ring_bbox(r, CAMPUS_BUFFER_M)) for r in rings]
    numbered = [(r, ring_bbox(r, BUILDING_BUFFER_M)) for r in polygons]
    building_points = [(b["lat"], b["lon"]) for b in buildings]

    def near(p):
        if any(near_ring(p, r, CAMPUS_BUFFER_M, bb) for r, bb in campus):
            return True
        if any(dist_m(p[0], p[1], q[0], q[1]) <= BUILDING_BUFFER_M for q in building_points):
            return True
        return any(near_ring(p, r, BUILDING_BUFFER_M, bb) for r, bb in numbered)

    is_near = [near(p) for p in pts]
    keep = {i for i in range(len(g.ea)) if is_near[g.ea[i]] and is_near[g.eb[i]]}
    reason = Counter(area=len(keep))

    pair_edges = defaultdict(list)
    for i in range(len(g.ea)):
        pair_edges[frozenset((g.ea[i], g.eb[i]))].append(i)
    for s in src["segments"]:
        path = list(map(int, s["graph_node_path"].split()))
        for a, b in zip(path, path[1:]):
            for i in pair_edges[frozenset((a, b))]:
                if i not in keep:
                    keep.add(i)
                    reason["bus"] += 1

    for b in buildings:
        for profile in PROFILES:
            dist, prev, _ = g.dijkstra(b["access_nodes"], profile)
            for c in buildings:
                if c is b:
                    continue
                end = min(c["access_nodes"], key=lambda x: dist[x])
                if dist[end] == math.inf:
                    continue
                for i, _ in g.path_edges(prev, end):
                    if i not in keep:
                        keep.add(i)
                        reason["building_route"] += 1

    alive = {x for i in keep for x in (g.ea[i], g.eb[i])}
    for i in range(len(g.ea)):
        if g.kind[i] in (K_CONNECTOR, K_INDOOR) and i not in keep and g.ea[i] in alive and g.eb[i] in alive:
            keep.add(i)
            reason["connector_indoor"] += 1

    adj = defaultdict(list)
    for i in keep:
        adj[g.ea[i]].append(g.eb[i])
        adj[g.eb[i]].append(g.ea[i])
    comp = {}
    for s in adj:
        if s in comp:
            continue
        comp[s] = s
        stack = [s]
        while stack:
            u = stack.pop()
            for v in adj[u]:
                if v not in comp:
                    comp[v] = s
                    stack.append(v)
    big = Counter(comp.values()).most_common(1)[0][0]
    final = {i for i in keep if comp[g.ea[i]] == big}
    reason["dropped_small_pieces"] = len(keep) - len(final)
    return final, reason


def build(src):
    lat = [float(r["lat"]) for r in src["nodes"]]
    lon = [float(r["lon"]) for r in src["nodes"]]
    E = src["edges"]
    elev = {}
    for e in E:
        for col_n, col_e in (("a", "elev_a_m"), ("b", "elev_b_m")):
            n, v = int(e[col_n]), float(e[col_e])
            if n in elev and abs(elev[n] - v) > 1e-9:
                raise SystemExit(f"정점 {n}의 고도가 간선마다 다르다: {elev[n]} vs {v}")
            elev[n] = v
    full = Graph(lat, lon, [(int(e["a"]), int(e["b"]), int(e["kind"]), e["covered"] == "1",
                             float(e["elev_a_m"]), float(e["elev_b_m"])) for e in E])

    buildings = src["buildings"]
    features = src["polygons"]
    structures = osm_structures(src["osm"])
    if len(structures) != len(features):
        raise SystemExit(f"OSM 건물 폴리곤 {len(structures)}개와 geojson {len(features)}개가 다르다")
    for s, f in zip(structures, features):
        ring = [(c[1], c[0]) for c in f["geometry"]["coordinates"][0]]
        if len(ring) != len(s["ring"]) or max(dist_m(*p, *q) for p, q in zip(ring, s["ring"])) > 0.5:
            raise SystemExit(f"geojson 폴리곤 {f['properties']['idx']}이 OSM 건물과 맞지 않는다")
    poly_of = {}
    for f in features:
        for no in f["properties"]["building_nos"]:
            poly_of[no] = f["properties"]["idx"]
    numbered_rings = [s["ring"] for s, f in zip(structures, features) if f["properties"]["building_nos"]]

    keep, reason = prune(full, src, campus_rings(src["osm"]), numbered_rings, buildings)
    alive = sorted({x for i in keep for x in (full.ea[i], full.eb[i])})
    node_id = {old: new for new, old in enumerate(alive, start=1)}

    incident = defaultdict(list)
    for e in E:
        incident[int(e["a"])].append(e)
        incident[int(e["b"])].append(e)
    node_rows = []
    for old in alive:
        interpolated = all(e["bridge_tunnel"] == "1" for e in incident[old])
        node_rows.append((node_id[old], lat[old], lon[old], elev[old], "INTERPOLATED" if interpolated else "DEM"))

    grid = defaultdict(list)
    cell = 0.0002
    for i in range(len(lat)):
        grid[(int(lat[i] / cell), int(lon[i] / cell))].append(i)
    added_for_entrance = {int(e["a"]) for e in E if int(e["kind"]) == K_CONNECTOR}
    walk_osm_nodes = walkable_osm_nodes(src["osm"])

    def graph_node_at(osm_node):
        la, lo = osm_node["lat"], osm_node["lon"]
        want_added = osm_node["id"] not in walk_osm_nodes
        cy, cx = int(la / cell), int(lo / cell)
        cands = []
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                for i in grid.get((cy + dy, cx + dx), ()):
                    d = dist_m(la, lo, lat[i], lon[i])
                    if d <= ENTRANCE_MATCH_M:
                        cands.append(((i in added_for_entrance) != want_added, d, i))
        return min(cands)[2] if cands else None

    structure_rows, entrance_rows = [], []
    entrances_of = defaultdict(set)
    wheelchair = {}
    not_on_graph = 0
    for idx, (s, f) in enumerate(zip(structures, features)):
        ring = [{"latitude": c[1], "longitude": c[0]} for c in f["geometry"]["coordinates"][0][:-1]]
        structure_rows.append((idx + 1, f["properties"]["osm_name"] or "", ring, floors_of(s["tags"])))
        for ent in s["entrances"]:
            n = graph_node_at(ent)
            if n is None:
                not_on_graph += 1
                continue
            entrances_of[idx].add(n)
            wheelchair[n] = wheelchair_of(ent["tags"])

    for b in buildings:
        if b["access_estimated"]:
            continue
        expected = set(b["access_nodes"])
        got = entrances_of.get(poly_of.get(b["no"]), set())
        if expected != got:
            raise SystemExit(f"{b['no']}동 출입구가 원본 접근 정점과 다르다: {sorted(expected)} vs {sorted(got)}")

    entrance_id = 0
    structures_of_node = defaultdict(set)
    for idx in sorted(entrances_of):
        for n in sorted(entrances_of[idx]):
            structures_of_node[n].add(idx)
            if n not in node_id:
                continue
            entrance_id += 1
            entrance_rows.append((entrance_id, idx + 1, node_id[n], wheelchair.get(n)))

    edge_rows, seen_edges, duplicate_edges = [], {}, 0
    for i in sorted(keep):
        e = E[i]
        structure = None
        if int(e["kind"]) == K_INDOOR:
            both = structures_of_node[int(e["a"])] & structures_of_node[int(e["b"])]
            if len(both) != 1:
                raise SystemExit(f"건물 관통 간선 {i}의 건물을 하나로 정할 수 없다: {both}")
            structure = next(iter(both)) + 1
        a, b = sorted((node_id[int(e["a"])], node_id[int(e["b"])]))
        row = (a, b, KIND_NAMES[int(e["kind"])], structure, e["covered"] == "1", e["bridge_tunnel"] == "1", e["estimated"] == "1")
        if row[:3] in seen_edges:
            if seen_edges[row[:3]] != row:
                raise SystemExit(f"같은 두 정점을 잇는 {row[2]} 간선의 속성이 다르다: {seen_edges[row[:3]]} vs {row}")
            duplicate_edges += 1
            continue
        seen_edges[row[:3]] = row
        edge_rows.append((len(edge_rows) + 1, *row))

    building_rows, access_rows = [], []
    for bid, b in enumerate(buildings, start=1):
        p = poly_of.get(b["no"])
        building_rows.append((bid, b["no"], "GWANAK", b["name"], b["name_en"] or "", b["lat"], b["lon"],
                              None if p is None else p + 1))
        if b["access_estimated"]:
            for n in b["access_nodes"]:
                access_rows.append((len(access_rows) + 1, bid, node_id[n]))

    stop_rows = [(int(s["idx"]) + 1, s["name"], s["ars_id"] or None, float(s["lat"]), float(s["lon"]),
                  node_id[int(s["graph_node"])]) for s in src["stops"]]
    stop_name = {int(s["idx"]): s["name"] for s in src["stops"]}
    segs_of = defaultdict(list)
    for s in src["segments"]:
        segs_of[int(s["line_idx"])].append(s)
    line_rows, line_stop_rows = [], []
    line_id_of, runs = {}, Counter()
    for li, ln in enumerate(src["transit"]["lines"]):
        segs = sorted(segs_of[li], key=lambda s: int(s["seq"]))
        if [int(s["from_stop_idx"]) for s in segs] + [int(segs[-1]["to_stop_idx"])] != ln["stops"]:
            raise SystemExit(f"노선 {ln['name']}({li})의 정류장 순서가 transit.json과 다르다")
        if ln["name"] not in line_id_of:
            line_id_of[ln["name"]] = len(line_rows) + 1
            kind = {"city": "CITY_BUS", "shuttle": "SHUTTLE"}[ln["kind"]]
            if ln["name"] in HEADWAYS:
                low, high, note, estimated = HEADWAYS[ln["name"]]
            else:
                low, high = map(int, ln["hwsrc"].rstrip("분").split("~"))
                note, estimated = ln["hwsrc"], True
            line_rows.append((line_id_of[ln["name"]], ln["name"], kind, low, high, note, estimated))
        run_no = runs[ln["name"]]
        runs[ln["name"]] += 1
        for seq, stop in enumerate(ln["stops"]):
            seg = segs[seq] if seq < len(segs) else None
            shown = seg["from_name"] if seg else segs[-1]["to_name"]
            path = None
            if seg:
                path = [{"latitude": lat[v], "longitude": lon[v]} for v in map(int, seg["graph_node_path"].split())]
            line_stop_rows.append((len(line_stop_rows) + 1, line_id_of[ln["name"]], run_no, seq, stop + 1,
                                   shown if shown != stop_name[stop] else None,
                                   int(seg["ride_sec_estimated"]) if seg else None,
                                   int(seg["road_m"]) if seg else None, path))

    return {
        "node": node_rows, "structure": structure_rows, "edge": edge_rows, "building": building_rows,
        "entrance": entrance_rows, "access_point": access_rows, "stop": stop_rows, "line": line_rows,
        "line_stop": line_stop_rows,
        "_report": {"reason": reason, "dropped_edges": len(E) - len(keep), "dropped_nodes": len(lat) - len(alive),
                    "dropped_km": round(sum(full.length[i] for i in range(len(E)) if i not in keep) / 1000, 1),
                    "entrances_not_on_graph": not_on_graph, "duplicate_edges": duplicate_edges},
    }


def verify(rows, vectors):
    idx = {r[0]: k for k, r in enumerate(rows["node"])}
    g = Graph([r[1] for r in rows["node"]], [r[2] for r in rows["node"]], [
        (idx[r[1]], idx[r[2]], KIND_NAMES.index(r[3]), r[5], rows["node"][idx[r[1]]][3], rows["node"][idx[r[2]]][3])
        for r in rows["edge"]])
    entrances = defaultdict(list)
    for _, structure, node, _ in rows["entrance"]:
        entrances[structure].append(idx[node])
    estimated = defaultdict(list)
    for _, building, node in rows["access_point"]:
        estimated[building].append(idx[node])
    access = {}
    for bid, no, *_, structure in rows["building"]:
        connected = [n for n in entrances.get(structure, []) if g.adj[n]]
        access[no] = connected or estimated[bid]
        if not access[no]:
            raise SystemExit(f"{no}동에 접근 정점이 없다")

    diff = []
    for v in vectors:
        for profile in PROFILES:
            t = g.route_time(access[v["from"]], access[v["to"]], profile)
            got = None if t is None else round(t * 10) / 10
            want = v[profile]["time_sec"] if v[profile] else None
            if got != want:
                diff.append(f"{v['from']}->{v['to']} {profile}: {want} -> {got}")
    unreachable = 0
    for a, src in access.items():
        dist, _, _ = g.dijkstra(src, "fast")
        unreachable += sum(1 for b, dst in access.items() if b != a and min(dist[x] for x in dst) == math.inf)
    return len(vectors) * len(PROFILES) - len(diff), len(vectors) * len(PROFILES), diff, unreachable, len(access)


class Raw(str):
    pass


NOW = Raw("@now")


def sql_value(v):
    if v is None:
        return "NULL"
    if isinstance(v, Raw):
        return v
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    if isinstance(v, (int, float)):
        return repr(v)
    if isinstance(v, (list, dict)):
        v = json.dumps(v, ensure_ascii=False, separators=(",", ":"))
    return "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def insert(out, table, columns, rows):
    for k in range(0, len(rows), INSERT_CHUNK):
        out.write(f"INSERT INTO {table} ({', '.join(columns)}) VALUES\n")
        out.write(",\n".join("(" + ", ".join(sql_value(v) for v in r) + ")" for r in rows[k:k + INSERT_CHUNK]))
        out.write(";\n")


def write_sql(path, rows):
    stamp = lambda r, *extra: (*r, *extra, NOW, NOW)
    with open(path, "w", encoding="utf-8") as out:
        out.write("-- 캠퍼스 길찾기 초기 데이터. scripts/campus-map/initial_load.py가 만들었다. 직접 고치지 않는다.\n")
        out.write("-- 출처: OpenStreetMap(ODbL), 서울대 캠퍼스맵, AWS Terrain Tiles, 서울 열린데이터광장, 서울대 학내교통 안내\n")
        out.write("SET NAMES utf8mb4;\nSET @now = UTC_TIMESTAMP(6);\nSTART TRANSACTION;\n\n")
        insert(out, "map_walk_node",
               ["id", "latitude", "longitude", "elevation_m", "elevation_source", "source", "created_at", "updated_at"],
               [stamp(r, "INITIAL") for r in rows["node"]])
        insert(out, "map_structure", ["id", "name", "polygon", "floors", "source", "created_at", "updated_at"],
               [stamp(r, "INITIAL") for r in rows["structure"]])
        insert(out, "map_walk_edge",
               ["id", "node_a_id", "node_b_id", "kind", "structure_id", "is_covered", "is_bridge_or_tunnel",
                "is_estimated", "source", "created_at", "updated_at"],
               [stamp(r, "INITIAL") for r in rows["edge"]])
        insert(out, "map_building",
               ["id", "building_number", "campus", "name_kor", "name_eng", "latitude", "longitude", "structure_id",
                "source", "created_at", "updated_at"],
               [stamp(r, "INITIAL") for r in rows["building"]])
        insert(out, "map_entrance",
               ["id", "structure_id", "node_id", "is_wheelchair_accessible", "source", "created_at", "updated_at"],
               [stamp(r, "INITIAL") for r in rows["entrance"]])
        insert(out, "map_access_point", ["id", "building_id", "node_id", "created_at", "updated_at"],
               [stamp(r) for r in rows["access_point"]])
        insert(out, "map_transit_stop",
               ["id", "name", "ars_id", "latitude", "longitude", "node_id", "source", "created_at", "updated_at"],
               [stamp(r, "INITIAL") for r in rows["stop"]])
        insert(out, "map_transit_line",
               ["id", "name", "kind", "min_headway_min", "max_headway_min", "headway_note", "is_headway_estimated",
                "source", "created_at", "updated_at"],
               [stamp(r, "INITIAL") for r in rows["line"]])
        insert(out, "map_transit_line_stop",
               ["id", "line_id", "run_no", "seq", "stop_id", "display_name", "ride_sec_to_next", "road_m_to_next",
                "path_to_next", "created_at", "updated_at"],
               [stamp(r) for r in rows["line_stop"]])
        out.write("UPDATE map_state SET version = version + 1, updated_at = @now WHERE id = 1;\n\nCOMMIT;\n")


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: python3 scripts/campus-map/initial_load.py <handoff 폴더> <출력 .sql>")
    handoff, out = sys.argv[1], sys.argv[2]
    src = read_inputs(handoff)
    rows = build(src)
    ok, total, diff, unreachable, n_buildings = verify(rows, src["vectors"])
    report = rows.pop("_report")
    log = lambda *a: print(*a, file=sys.stderr)
    log(f"등산로 제외: 간선 {report['dropped_edges']}개({report['dropped_km']}km), 정점 {report['dropped_nodes']}개를 뺐다. "
        f"남긴 이유별 간선 수 {dict(report['reason'])}")
    log(f"그래프에 없는 OSM 출입구 {report['entrances_not_on_graph']}개는 넣지 않았다(길에서 45m 넘게 떨어져 원본에서도 빠짐)")
    log(f"같은 두 정점을 잇는 완전히 같은 간선 {report['duplicate_edges']}개는 하나만 남겼다")
    log(f"검증: 테스트 벡터 도보 경로 {ok}/{total} 일치, 경로 없는 강의동 쌍 {unreachable}/{n_buildings * (n_buildings - 1)}")
    if diff or unreachable:
        for d in diff[:20]:
            log("  DIFF", d)
        raise SystemExit("검증 실패. SQL을 쓰지 않았다")
    write_sql(out, rows)
    for table in ("node", "edge", "structure", "building", "entrance", "access_point", "stop", "line", "line_stop"):
        log(f"  {table:12s} {len(rows[table]):5d}행")
    log(f"-> {out}")


if __name__ == "__main__":
    main()
