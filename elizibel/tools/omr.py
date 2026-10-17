"""
Lector de partituras vectoriales (PDF exportado de MuseScore con fuentes SMuFL: Leland, MScore, Bravura).
No adivina: cada nota sale de un glifo de cabeza de nota y su posicion exacta respecto a las lineas del pentagrama.
Duraciones: glifo (redonda/blanca/negra) + haces o banderas en la plica + puntillos + grupos irregulares.
Inicio de cada nota: segmentos verticales del sistema (MuseScore alinea en el mismo x todo lo que suena a la vez).
"""
import pdfplumber, re, json, sys, collections
from fractions import Fraction as F

Q = 480  # ticks por negra

HEADS = {0xE0A2: 4 * Q, 0xE0A3: 2 * Q, 0xE0A4: Q, 0xE0A0: 8 * Q}
RESTS = {0xE4E3: 4 * Q, 0xE4E4: 2 * Q, 0xE4E5: Q, 0xE4E6: Q // 2, 0xE4E7: Q // 4, 0xE4E8: Q // 8, 0xE4E9: Q // 16}
FLAGS = {0xE240: 1, 0xE241: 1, 0xE242: 2, 0xE243: 2, 0xE244: 3, 0xE245: 3, 0xE246: 4, 0xE247: 4}
ACC = {0xE260: -1, 0xE261: 0, 0xE262: 1, 0xE263: 2, 0xE264: -2}
DOT = 0xE1E7
G_CLEF, F_CLEF = (0xE050, 0xE051, 0xE052, 0xE053, 0xE054), (0xE062, 0xE064, 0xE065)
CLEF_OCT = {0xE051: -24, 0xE052: -12, 0xE053: 12, 0xE054: 24, 0xE064: -12, 0xE065: 12}
OTTAVA = {0xE510: 12, 0xE511: 12, 0xE512: 24, 0xE513: 24, 0xE51C: 12, 0xE51D: 12, 0xE51A: -12, 0xE51B: -12}
STEPS = [0, 2, 4, 5, 7, 9, 11]
NAMES = "CDEFGAB"


EXTRA_FONTS = []
LINE_TOL = 0.35


def is_music_font(fn):
    if any(fn.endswith(x) for x in EXTRA_FONTS):
        return True
    fn = fn.split('+')[-1]
    return any(k in fn for k in ("Leland", "MScore", "Bravura")) and "Text" not in fn


def midi_of(d, alt):
    return 12 * (d // 7 + 1) + STEPS[d % 7] + alt


def name_of(d, alt):
    a = {-2: 'bb', -1: 'b', 0: '', 1: '#', 2: 'x'}[alt]
    return NAMES[d % 7] + a + str(d // 7)


class G:  # glifo
    __slots__ = ("code", "x", "y", "w", "size", "used")

    def __init__(s, code, x, y, w, size):
        s.code, s.x, s.y, s.w, s.size, s.used = code, x, y, w, size, False


def page_objects(pg):
    H = pg.height
    gl, txt = [], []
    for c in pg.chars:
        t = c['text']
        if not t:
            continue
        cp = ord(t[0])
        x = c['matrix'][4]; y = c['_y'] if '_y' in c else H - c['matrix'][5]
        if 0xE000 <= cp <= 0xF8FF and is_music_font(c['fontname']):
            gl.append(G(cp, x, y, c['x1'] - c['x0'], c['size']))
        elif 0xE000 <= cp <= 0xF8FF:
            gl.append(G(cp, x, y, c['x1'] - c['x0'], -c['size']))  # glifo de fuente de texto (tuplets, tempo)
        else:
            txt.append(dict(t=t, x=c['x0'], x1=c['x1'], y=y, top=c['top'], size=c['size'], font=c['fontname'].split('+')[-1]))
    return gl, txt


def find_staves(pg, sp_hint=None):
    hl = [l for l in pg.lines if abs(l['top'] - l['bottom']) < 0.05 and l['x1'] - l['x0'] > 15]
    lwc = collections.Counter(round(l['linewidth'], 2) for l in hl)
    rows = collections.defaultdict(list)
    for l in hl:
        rows[round(l['top'], 1)].append(l)
    tot = {y: sum(l['x1'] - l['x0'] for l in rows[y]) for y in rows}
    mx = max(tot.values()) if tot else 0
    ys = sorted(y for y in rows if tot[y] >= mx * 0.45)
    # agrupar en pentagramas de 5 lineas equidistantes
    staves = []
    used = set()
    for i, y in enumerate(ys):
        if y in used:
            continue
        for j in range(i + 1, min(i + 3, len(ys))):
            d = ys[j] - y
            if d < 3 or d > 60:
                continue
            cand = [y + k * d for k in range(5)]
            got = []
            for cy in cand:
                m = [yy for yy in ys if abs(yy - cy) < LINE_TOL and yy not in used]
                if not m:
                    break
                got.append(m[0])
            if len(got) == 5:
                xs0 = min(l['x0'] for yy in got for l in rows[yy]); xs1 = max(l['x1'] for yy in got for l in rows[yy])
                # largo comun minimo: las 5 lineas deben cubrir lo mismo
                staves.append(dict(lines=got, sp=(got[4] - got[0]) / 4, x0=xs0, x1=xs1))
                used.update(got)
                break
    staves.sort(key=lambda s: s['lines'][0])
    return staves


class Event:
    def __init__(s, kind, x, staff):
        s.kind = kind  # 'n' nota/acorde, 'r' silencio
        s.x = x; s.staff = staff
        s.heads = []  # (y, glyph)
        s.base = Q; s.beams = 0; s.dots = 0; s.stem = 'n'; s.stem_x = None; s.stem_end = None
        s.tuplet = None; s.grace = False; s.measure_rest = False
        s.beam_id = None; s.onset = None; s.dur = None; s.notes = []


def analyze_page(pg, cfg, carry):
    gl, txt = page_objects(pg)
    staves = find_staves(pg)
    if not staves:
        return []
    sp = collections.Counter(round(s['sp'], 2) for s in staves).most_common(1)[0][0]
    music_size = collections.Counter(round(g.size, 1) for g in gl if g.code in HEADS and g.size > 0).most_common(1)
    msize = music_size[0][0] if music_size else 4 * sp
    vlines = [l for l in pg.lines if abs(l['x0'] - l['x1']) < 0.05 and l['bottom'] - l['top'] > 0.4]
    beams = []
    for c in list(pg.curves) + list(pg.rects):
        if c['object_type'] == 'rect':
            if c['fill'] and c['x1'] - c['x0'] > sp * 0.8 and c['bottom'] - c['top'] < sp * 0.9:
                beams.append([(c['x0'], c['top']), (c['x1'], c['top']), (c['x1'], c['bottom']), (c['x0'], c['bottom'])])
            continue
        ops = [o[0] for o in c['path']]
        if ops[:1] == ['m'] and all(o in ('l', 'h') for o in ops[1:]) and len(c['pts']) >= 4 and c['fill']:
            pts = c['pts'][:4]
            xs = [p[0] for p in pts]
            if max(xs) - min(xs) > sp * 0.8 and max(p[1] for p in pts) - min(p[1] for p in pts) < sp * 6:
                beams.append(pts)
        elif ops == ['m', 'l', 'l', 'l', 'h'] or ops == ['m', 'l', 'l', 'l', 'l', 'h']:
            pass
    # sistemas: grupos de pentagramas unidos por barras de compas
    systems = group_systems(staves, vlines, sp, cfg)
    out = []
    for sysi, st_idx in enumerate(systems):
        st = [staves[i] for i in st_idx]
        t0 = st[0]['lines'][0] - sp * 9
        b0 = st[-1]['lines'][4] + sp * 9
        if sysi > 0:
            prev = staves[systems[sysi - 1][-1]]['lines'][4]
            t0 = max(t0, (prev + st[0]['lines'][0]) / 2)
        if sysi + 1 < len(systems):
            nxt = staves[systems[sysi + 1][0]]['lines'][0]
            b0 = min(b0, (st[-1]['lines'][4] + nxt) / 2)
        out.append(analyze_system(pg, st, gl, txt, vlines, beams, sp, msize, cfg, (t0, b0)))
    return out


def group_systems(staves, vlines, sp, cfg):
    n = cfg.get('staves_per_system')
    if n:
        return [list(range(i, i + n)) for i in range(0, len(staves) - n + 1, n)]
    # por barras de compas que unen pentagramas
    groups = [[0]]
    for i in range(1, len(staves)):
        a, b = staves[i - 1], staves[i]
        joined = any(l['top'] <= a['lines'][4] + 0.5 and l['bottom'] >= b['lines'][0] - 0.5 for l in vlines)
        if joined:
            groups[-1].append(i)
        else:
            groups.append([i])
    return groups


def staff_of(y, staves, sp):
    best, bd = None, 1e9
    for i, s in enumerate(staves):
        top, bot = s['lines'][0], s['lines'][4]
        d = 0 if top <= y <= bot else min(abs(y - top), abs(y - bot))
        if d < bd:
            best, bd = i, d
    return best if bd < sp * 7 else None


def analyze_system(pg, staves, gl, txt, vlines, beams, sp, msize, cfg, bounds):
    top, bot = bounds
    X0 = min(s['x0'] for s in staves); X1 = max(s['x1'] for s in staves)
    G_ = [g for g in gl if top <= g.y <= bot and X0 - sp <= g.x <= X1 + sp]
    hw = sp * 1.18
    # barras de compas: lineas verticales que van de la primera linea a la ultima de algun pentagrama
    bars = []
    for l in vlines:
        for s in staves:
            if abs(l['top'] - s['lines'][0]) < sp * 0.3 and l['bottom'] >= s['lines'][4] - sp * 0.3:
                bars.append((l['x0'], l['linewidth']))
                break
    bars.sort()
    bx = []
    for x, w in bars:
        if bx and abs(x - bx[-1][0]) < sp * 1.2:
            bx[-1][1].append((x, w)); bx[-1][0] = x
        else:
            bx.append([x, [(x, w)]])
    barxs = [b[0] for b in bx]
    # quitar posibles plicas confundidas: una barra no tiene cabeza pegada
    heads = [g for g in G_ if g.code in HEADS and g.size > 0]
    def near_head(x):
        return any(abs(h.x - x) < sp * 0.25 or abs(h.x + hw - x) < sp * 0.25 for h in heads if h.size > msize * 0.85)
    barxs = [x for x in barxs if not near_head(x)]
    # inicio del primer compas: despues de clave, armadura y cifra
    stems = [l for l in vlines if not any(abs(l['x0'] - b) < sp * 0.2 for b in barxs)]

    res = dict(staves=[], measures=[], sp=sp)
    events = []
    clefs = collections.defaultdict(list)  # staff -> [(x, kind)]
    for g in G_:
        if g.code in G_CLEF + F_CLEF:
            si = staff_of(g.y, staves, sp)
            if si is not None:
                kind = 'g' if g.code in G_CLEF else 'f'
                oct8 = CLEF_OCT.get(g.code, 0)
                clefs[si].append((g.x, kind, oct8))
    for si in clefs:
        clefs[si].sort()
    # cabezas -> eventos por plica
    used_heads = set()
    ev_by_stem = {}
    for h in heads:
        si = staff_of(h.y, staves, sp)
        if si is None:
            continue
        si = 0
        grace = h.size < msize * 0.85
        best = None; bd = 1e9
        if h.code != 0xE0A2 and h.code != 0xE0A0:
            hwid = hw * (h.size / msize)
            for l in stems:
                if l['top'] - sp * 0.6 <= h.y <= l['bottom'] + sp * 0.6:
                    for edge in (h.x + hwid, h.x):
                        d = abs(l['x0'] - edge)
                        if d < sp * 0.3:
                            # la cabeza debe estar en un extremo o dentro del tramo de la plica
                            dy = min(abs(h.y - l['top']), abs(h.y - l['bottom']))
                            score = d + (0 if l['top'] - sp * 0.6 <= h.y <= l['bottom'] + sp * 0.6 else 9) + dy * 0.01
                            if score < bd:
                                bd, best = score, l
        key = id(best) if best is not None else ('h', round(h.x / (sp * 0.6)), staff_of(h.y, staves, sp), h.code)
        if key not in ev_by_stem:
            e = Event('n', h.x, si); e.grace = grace
            e.base = HEADS[h.code]
            if best is not None:
                e.stem_x = best['x0']
                # direccion: si la plica sube desde la cabeza
                e.stem = 'u' if abs(h.y - best['bottom']) < abs(h.y - best['top']) else 'd'
                e.stem_top, e.stem_bot = best['top'], best['bottom']
            ev_by_stem[key] = e
            events.append(e)
        ev_by_stem[key].heads.append(h)
    for e in events:
        if e.stem_x is not None:
            ys = [h.y for h in e.heads]
            dt = min(ys) - e.stem_top; db = e.stem_bot - max(ys)
            e.stem = 'u' if dt > db else 'd'
            right = sum(1 for h in e.heads if abs(h.x + hw * h.size / msize - e.stem_x) < sp * 0.3)
            left = len(e.heads) - right
            if right > left or (right == left and e.stem == 'u'):
                e.x = e.stem_x - hw
            else:
                e.x = e.stem_x
        else:
            e.x = min(h.x for h in e.heads)
    # pentagrama de cada evento: las cabezas entre dos pentagramas se resuelven por grupo de haz o lineas adicionales
    ledgers = [l for l in pg.lines if abs(l['top'] - l['bottom']) < 0.05 and sp * 1.2 < l['x1'] - l['x0'] < sp * 3.2]
    def amb(y):
        for i in range(len(staves) - 1):
            if staves[i]['lines'][4] + sp * 0.3 < y < staves[i + 1]['lines'][0] - sp * 0.3:
                return i
        return None
    for e in events:
        fixed = [staff_of(h.y, staves, sp) for h in e.heads if amb(h.y) is None]
        e.amb = None
        if fixed:
            e.staff = collections.Counter(fixed).most_common(1)[0][0]
        else:
            e.amb = amb(e.heads[0].y)
    for e in events:
        if e.amb is None:
            continue
        i = e.amb
        if e.stem in ('u', 'd') and e.stem_x is not None:
            e.staff = i if e.stem == 'u' else i + 1
            e.needs_beam_staff = True
            continue
        h = e.heads[0]
        below = [l for l in ledgers if l['x0'] - sp * 0.6 < h.x < l['x1'] and h.y + sp * 0.3 < l['top'] < staves[i + 1]['lines'][0] - 0.2]
        above = [l for l in ledgers if l['x0'] - sp * 0.6 < h.x < l['x1'] and staves[i]['lines'][4] + 0.2 < l['top'] < h.y - sp * 0.3]
        if below and not above:
            e.staff = i + 1
        elif above and not below:
            e.staff = i
        else:
            e.staff = None
    for e in events:
        if e.amb is not None and e.staff is None:
            e.staff = staff_of(e.heads[0].y, staves, sp)
            e.needs_beam_staff = True
    for e in events:
        if e.stem_x is not None and e.base == Q:
            end = e.stem_top if e.stem == 'u' else e.stem_bot
            inward = 1 if e.stem == 'u' else -1
            n = 0
            for b in beams:
                xs = [p[0] for p in b]; bx0, bx1 = min(xs), max(xs)
                if bx0 - sp * 0.15 <= e.stem_x <= bx1 + sp * 0.15:
                    # y del haz en la x de la plica
                    ys = sorted(b, key=lambda p: p[0])
                    left = [p for p in b if abs(p[0] - bx0) < 0.5]; right = [p for p in b if abs(p[0] - bx1) < 0.5]
                    if not left or not right:
                        continue
                    yl = sum(p[1] for p in left) / len(left); yr = sum(p[1] for p in right) / len(right)
                    t = (e.stem_x - bx0) / max(0.01, bx1 - bx0)
                    yb = yl + (yr - yl) * t
                    rel = (yb - end) * inward
                    if -sp * 0.6 <= rel <= sp * 3.2:
                        n += 1
                        if e.beam_id is None or bx1 - bx0 > getattr(e, 'beam_w', 0):
                            e.beam_id = id(b); e.beam_w = bx1 - bx0
            fl = 0
            for g in G_:
                if g.code in FLAGS and abs(g.x - e.stem_x) < sp * 0.5 and abs(g.y - end) < sp * 1.2:
                    fl = max(fl, FLAGS[g.code])
            e.beams = max(n, fl)
            e.base = Q >> e.beams if e.beams else Q
    groups = collections.defaultdict(list)
    for e in events:
        if e.beam_id is not None:
            groups[e.beam_id].append(e)
    for e in events:
        if getattr(e, 'needs_beam_staff', False) and e.beam_id is not None:
            v = [o.staff for o in groups[e.beam_id] if o.amb is None]
            if v:
                e.staff = collections.Counter(v).most_common(1)[0][0]
    # silencios
    for g in G_:
        if g.code in RESTS and g.size > msize * 0.85:
            si = staff_of(g.y, staves, sp)
            if si is None:
                continue
            e = Event('r', g.x, si)
            e.base = RESTS[g.code]
            e.rest_y = g.y; e.rest_code = g.code
            e.heads = []
            events.append(e)
    # puntillos
    for g in G_:
        if g.code != DOT:
            continue
        best, bd = None, 1e9
        for e in events:
            if e.kind == 'n':
                for h in e.heads:
                    dx = g.x - (h.x + hw * h.size / msize)
                    if 0 < dx < sp * 2.6 and abs(g.y - h.y) <= sp * 0.8:
                        if dx < bd:
                            bd, best = dx, e
            else:
                dx = g.x - e.x
                if sp * 0.5 < dx < sp * 3 and abs(g.y - e.rest_y) < sp * 1.6:
                    if dx < bd:
                        bd, best = dx, e
        if best is not None:
            best.dotset = getattr(best, 'dotset', set()); best.dotset.add(round(g.x, 1))
    for e in events:
        e.dots = len(getattr(e, 'dotset', ()))
    # grupos irregulares (tresillos, seisillos): digitos de la fuente de tuplets o numeros en cursiva cerca de haces
    tuplets = [g for g in G_ if 0xE880 <= g.code <= 0xE889]
    tdig = [(g.x + abs(g.w) / 2, g.y, g.code - 0xE880) for g in tuplets]
    for t in txt:
        if t['t'].isdigit() and 'Italic' in t['font'] and cfg.get('italic_tuplets', True) and top <= t['y'] <= bot and t['size'] < 12:
            # en cursiva pequenos: o numero de compas (al inicio del sistema) o tuplet
            if t['x'] > X0 + sp * 6:
                tdig.append(((t['x'] + t['x1']) / 2, t['y'], int(t['t'])))
    for (tx, ty, num) in tdig:
        if num < 2:
            continue
        # haz cercano
        grp = []
        for b in beams:
            xs = [p[0] for p in b]
            if min(xs) - sp * 0.5 <= tx <= max(xs) + sp * 0.5:
                yb = sum(p[1] for p in b) / len(b)
                if abs(yb - ty) < sp * 3.5:
                    grp = [e for e in events if e.kind == 'n' and e.stem_x is not None and min(xs) - sp * 0.3 <= e.stem_x <= max(xs) + sp * 0.3
                           and e.beam_id is not None and staff_of(ty, staves, sp) == e.staff]
                    if grp:
                        break
        if not grp:
            # corchete: buscar eventos del pentagrama mas cercano dentro de +-3 espacios del numero
            si = staff_of(ty, staves, sp)
            brk = [l for l in pg.lines if abs(l['top'] - ty) < sp * 1.2 and abs(l['top'] - l['bottom']) < sp and l['x1'] - l['x0'] > sp]
            lx = [l for l in brk if l['x1'] <= tx and tx - l['x1'] < sp * 3]
            rx = [l for l in brk if l['x0'] >= tx and l['x0'] - tx < sp * 3]
            if lx and rx:
                x0 = min(l['x0'] for l in lx); x1 = max(l['x1'] for l in rx)
                grp = [e for e in events if e.staff == si and x0 - sp * 0.8 <= e.x <= x1 + sp * 0.3 and not e.grace]
        normal = 1
        while normal * 2 < num:
            normal *= 2
        for e in grp:
            e.tuplet = (num, normal)
    res['raw_events'] = events
    res['bars'] = barxs
    res['clefs'] = clefs
    res['staves'] = staves
    res['G'] = G_
    res['txt'] = [t for t in txt if top <= t['y'] <= bot]
    res['X0'], res['X1'] = X0, X1
    res['vlines'] = vlines
    res['curves'] = [c for c in pg.curves if c['top'] >= top and c['bottom'] <= bot]
    res['lines'] = [l for l in pg.lines if top <= l['top'] <= bot]
    return res


def staff_clef_at(res, si, x, default):
    k = default
    for (cx, kind, o) in res['clefs'].get(si, []):
        if cx <= x:
            k = kind
    return k


def clef_oct_at(res, si, x):
    o8 = 0; first = True
    for (cx, kind, o) in res['clefs'].get(si, []):
        if cx <= x or first:
            o8 = o
        first = False
    return o8


def pos_of(y, staff):
    sp = staff['sp']
    return round((staff['lines'][4] - y) / (sp / 2))



TS_DIG = {0xE080 + k: k for k in range(10)}


def finish_system(res, cfg, state):
    staves, sp, G_ = res['staves'], res['sp'], res['G']
    events = [e for e in res['raw_events'] if not e.grace]
    hw = sp * 1.18
    ns = len(staves)
    heads = [(h, e) for e in events if e.kind == 'n' for h in e.heads]
    accs = [g for g in G_ if g.code in ACC and g.size > 0]
    # armaduras por patron canonico (posiciones fijas desde la clave o desde una barra)
    PAT = {('g', 1): [8, 5, 9, 6, 3, 7, 4], ('g', -1): [4, 7, 3, 6, 2, 5, 1], ('f', 1): [6, 3, 7, 4, 1, 5, 2], ('f', -1): [2, 5, 1, 4, 0, 3, 6]}
    keyset = set()
    keyfound = []  # (si, x, value)
    starts = []
    for si in range(ns):
        for (cx, kind, o) in res['clefs'].get(si, []):
            starts.append((si, cx + sp * 2.2, kind))
        for b in res['bars']:
            starts.append((si, b + sp * 0.3, None))
    for (si, sx, kind) in starts:
        st = staves[si]
        kd = kind or staff_clef_at(res, si, sx, state['clef'].get(si, 'g'))
        cand = sorted([a for a in accs if staff_of(a.y, staves, sp) == si and sx - sp * 0.4 < a.x < sx + sp * 9], key=lambda a: a.x)
        val = 0; seq = []
        nat = [a for a in cand if ACC[a.code] == 0 and a.x < sx + sp * 4]
        for a in cand:
            v = ACC[a.code]
            if v not in (1, -1):
                if v == 0 and not seq:
                    continue
                break
            pat = PAT[(kd, v)]
            k = len(seq)
            if k >= 7 or pos_of(a.y, st) != pat[k] or (seq and (ACC[seq[-1].code] != v or a.x - seq[-1].x > sp * 1.7)):
                break
            if not seq and a.x - sx > sp * 3.5:
                break
            seq.append(a)
        if seq:
            # una sola alteracion despues de una barra puede ser de una nota: debe no tener cabeza cerca a su derecha
            if len(seq) == 1 and kind is None:
                a = seq[0]
                if any(0 < h.x - a.x < sp * 2.6 and abs(h.y - a.y) < sp * 0.3 for h, e in heads):
                    continue
            for a in seq:
                keyset.add(id(a))
            keyfound.append((si, seq[0].x, ACC[seq[0].code] * len(seq)))
        elif kind is None and nat and not any(0 < h.x - nat[0].x < sp * 2.6 and abs(h.y - nat[0].y) < sp * 0.3 for h, e in heads):
            for a in nat:
                keyset.add(id(a))
            keyfound.append((si, nat[0].x, 0))
    accs = [a for a in accs if id(a) not in keyset]
    attached = {}
    loose = collections.defaultdict(list)
    for a in accs:
        best, bd = None, 1e9
        for h, e in heads:
            dx = h.x - a.x
            if 0 < dx < sp * 6 and abs(h.y - a.y) < sp * 0.3:
                if dx < bd:
                    bd, best = dx, h
        if best is not None and (id(best) not in attached or attached[id(best)][1] > bd):
            attached[id(best)] = (a, bd)
        elif best is None:
            si = staff_of(a.y, staves, sp)
            if si is not None:
                loose[si].append(a)
    bars = sorted(res['bars'])
    edges = [res['X0']] + [b for b in bars if b > res['X0'] + sp * 2]
    if edges[-1] < res['X1'] - sp * 2:
        edges.append(res['X1'])
    # clave al inicio del sistema
    for si in range(ns):
        cl = res['clefs'].get(si)
        if cl:
            state['clef'][si] = cl[0][1]
    tsg = [g for g in G_ if g.code in TS_DIG or g.code in (0xE08A, 0xE08B)]
    out = []
    for mi in range(len(edges) - 1):
        a, b = edges[mi], edges[mi + 1]
        mev = [e for e in events if a < e.x < b]
        if not mev:
            continue
        fx = min(e.x for e in mev)
        # armadura y compas que aparecen entre la barra y la primera nota
        for (si, kx, kv) in keyfound:
            if a - sp * 0.5 < kx < fx:
                state['key'][si] = kv
        st0 = staves[0]
        tg = [g for g in tsg if a - sp * 0.5 < g.x < fx and staff_of(g.y, staves, sp) == 0]
        if tg:
            if tg[0].code == 0xE08A:
                state['time'] = (4, 4)
            elif tg[0].code == 0xE08B:
                state['time'] = (2, 2)
            else:
                mid = (st0['lines'][0] + st0['lines'][4]) / 2
                up = sorted([g for g in tg if g.y < mid + 0.1 and g.code in TS_DIG], key=lambda g: g.x)
                dn = sorted([g for g in tg if g.y > mid + 0.1 and g.code in TS_DIG], key=lambda g: g.x)
                if up and dn:
                    state['time'] = (int(''.join(str(TS_DIG[g.code]) for g in up)), int(''.join(str(TS_DIG[g.code]) for g in dn)))
        ts = state['time']
        mlen = Q * 4 * ts[0] // ts[1]
        for e in mev:
            d = e.base; add = d
            for k in range(e.dots):
                add //= 2; d += add
            if e.tuplet:
                n, nm = e.tuplet
                d = d * nm // n
            e.dur = d
            e.measure_rest = False
        for e in mev:
            if e.kind == 'r' and e.base == 4 * Q and e.dots == 0:
                e.dur = mlen; e.measure_rest = True
        xs = sorted([e for e in mev if not e.measure_rest], key=lambda e: e.x)
        segs = []
        for e in xs:
            if segs and e.x - segs[-1][0] < sp * 0.55:
                segs[-1][1].append(e)
            else:
                segs.append([e.x, [e]])
        t = 0
        active = []
        for i, (sx, se) in enumerate(segs):
            if i > 0:
                ends = [o.onset + o.dur for o in active if o.onset + o.dur > t]
                t = min(ends) if ends else t + min(o.dur for o in segs[i - 1][1])
            for e in se:
                e.onset = t
            active.extend(se)
        for e in mev:
            if e.measure_rest:
                e.onset = 0
        total = max([e.onset + e.dur for e in mev] + [0])
        # alturas con alteraciones accidentales que valen todo el compas
        accstate = {}
        for e in sorted(mev, key=lambda e: e.x):
            if e.kind != 'n':
                continue
            si = e.staff; st = staves[si]
            e.notes = []
            for h in sorted(e.heads, key=lambda h: -h.y):
                clef = staff_clef_at(res, si, h.x, state['clef'].get(si, 'g'))
                p = pos_of(h.y, st)
                d = (30 if clef == 'g' else 18) + p
                key = state['key'].get(si, 0)
                kalt = 0
                if key > 0 and d % 7 in [3, 0, 4, 1, 5, 2, 6][:key]:
                    kalt = 1
                if key < 0 and d % 7 in [6, 2, 5, 1, 4, 0, 3][:-key]:
                    kalt = -1
                acc = attached.get(id(h))
                shown = None
                if acc is not None:
                    shown = ACC[acc[0].code]
                    accstate[(si, d)] = shown
                alt = accstate.get((si, d), kalt)
                osh = 0
                for (ox0, ox1, osi, s8) in res.get('ottavas', []):
                    if osi == si and ox0 - sp * 0.5 <= h.x <= ox1 + sp * 0.5:
                        osh = s8
                co = clef_oct_at(res, si, h.x)
                e.notes.append(dict(d=d + co // 12 * 7, alt=alt, shown=shown, midi=midi_of(d, alt) + osh + co, y=h.y, x=h.x, oct=osh, hid=id(h)))
        out.append(dict(x0=a, x1=b, events=mev, total=total, mlen=mlen, time=ts, key=dict(state['key'])))
    return out


def find_ties(res, measures):
    """Ligaduras de prolongacion: arco plano entre dos cabezas de la misma altura."""
    sp = res['sp']
    allnotes = []
    for m in measures:
        for e in m['events']:
            for n in e.notes:
                allnotes.append((n, e))
    ties = []
    for c in res['curves']:
        ops = [o[0] for o in c['path']]
        if ops[:2] != ['m', 'c']:
            continue
        p0 = c['path'][0][1]; p3 = c['path'][1][3]
        if p3[0] < p0[0]:
            p0, p3 = p3, p0
        if abs(p3[1] - p0[1]) > sp * 0.6 or p3[0] - p0[0] < sp * 0.5:
            continue
        A = [(n, e) for n, e in allnotes if abs(n['y'] - p0[1]) < sp * 1.3 and -sp * 1.4 < p0[0] - (n['x'] + sp * 1.18) < sp * 0.9]
        B = [(n, e) for n, e in allnotes if abs(n['y'] - p3[1]) < sp * 1.3 and -sp * 0.9 < n['x'] - p3[0] < sp * 1.4]
        best = None
        for na, ea in A:
            for nb, eb in B:
                if eb is ea or abs(na['y'] - nb['y']) > sp * 0.2 or ea.staff != eb.staff:
                    continue
                if nb['x'] <= na['x']:
                    continue
                sc = abs(na['y'] - p0[1]) + abs(nb['y'] - p3[1])
                if best is None or sc < best[0]:
                    best = (sc, na, nb)
        if best:
            best[1]['tie_out'] = True; best[2]['tie_in'] = True
            ties.append(c)
        elif A and p3[0] > res['X1'] - sp * 2.5:
            # ligadura que sigue en el siguiente sistema
            na = min(A, key=lambda t: abs(t[0]['y'] - p0[1]))[0]
            na['tie_out'] = True; na['tie_next_sys'] = True
    return ties


def find_fingering(res, measures, cfg):
    sp = res['sp']
    digs = [t for t in res['txt'] if t['t'] in '12345' and cfg['finger_font'](t)]
    for t in digs:
        cx = (t['x'] + t['x1']) / 2
        best, bd = None, 1e9
        for m in measures:
            for e in m['events']:
                for n in e.notes:
                    dx = abs(n['x'] + sp * 0.59 - cx)
                    dy = abs(n['y'] - t['y'])
                    if dx < sp * 1.0 and dy < sp * 9:
                        sc = dx * 3 + dy
                        if sc < bd:
                            bd, best = sc, n
        if best is not None:
            best.setdefault('fing', []).append((t['y'], int(t['t'])))


def run(path, cfg):
    pdf = pdfplumber.open(path)
    state = dict(clef={}, key={}, time=cfg.get('time', (4, 4)))
    allm = []
    pages = cfg.get('pages') or range(len(pdf.pages))
    for pi in pages:
        pg = pdf.pages[pi]
        for res in analyze_page(pg, cfg, None):
            res['ottavas'] = find_ottavas(res)
            ms = finish_system(res, cfg, state)
            find_ties(res, ms)
            if cfg.get('finger_font'):
                find_fingering(res, ms, cfg)
            for m in ms:
                m['page'] = pi; m['res'] = res
            allm.extend(ms)
    return allm


def dur_name(d):
    names = {4*Q: 'w', 3*Q: 'h.', 2*Q: 'h', 3*Q//2: 'q.', Q: 'q', 3*Q//4: 'e.', Q//2: 'e', 3*Q//8: 's.', Q//4: 's', Q//8: 't', 7*Q//4: 'q..', 7*Q//2:'h..'}
    return names.get(d, str(d))


def dump(allm, staves=None, start=1):
    bad = 0
    for i, m in enumerate(allm):
        n = start + i
        ok = m['total'] == m['mlen']
        if not ok:
            bad += 1
        lines = []
        for si in sorted(set(e.staff for e in m['events'])):
            if staves is not None and si not in staves:
                continue
            evs = sorted([e for e in m['events'] if e.staff == si], key=lambda e: (e.onset, e.x))
            toks = []
            for e in evs:
                if e.kind == 'r':
                    toks.append(f"{e.onset}:{dur_name(e.dur)}:r")
                else:
                    ps = '+'.join(name_of(x['d'], x['alt']) + ('~' if x.get('tie_out') else '') + (f"[{x['oct']:+d}]" if x['oct'] else '') for x in e.notes)
                    toks.append(f"{e.onset}:{dur_name(e.dur)}:{ps}")
            lines.append(f"  s{si}: " + ' '.join(toks))
        print(f"m{n} {'OK ' if ok else 'BAD'} {m['total']}/{m['mlen']} t={m['time']} k={m['key']}")
        print('\n'.join(lines))
    print('compases', len(allm), 'con suma incorrecta', bad)


def check(allm, start=1, verbose=True):
    """Valida cada compas: suma exacta y que ninguna voz (misma plica) se solape a si misma."""
    probs = []
    for i, m in enumerate(allm):
        n = start + i
        msg = []
        if m['total'] != m['mlen']:
            msg.append(f"suma {m['total']}/{m['mlen']}")
        evs = [e for e in m['events'] if e.kind == 'n']
        for a in evs:
            for b in evs:
                if a is b or a.staff != b.staff or a.stem != b.stem or a.stem == 'n':
                    continue
                if a.onset < b.onset < a.onset + a.dur:
                    msg.append(f"solape s{a.staff} en {b.onset}")
                    break
        for e in m['events']:
            if e.kind == 'n' and not e.notes:
                msg.append('evento sin notas')
        if msg:
            probs.append((n, sorted(set(msg))))
    if verbose:
        for p in probs:
            print('m%d' % p[0], ', '.join(p[1]))
        print('compases:', len(allm), ' con problema:', len(probs))
    return probs


def numbering(allm, verbose=True):
    """Compara la numeracion impresa al inicio de cada sistema con el conteo propio."""
    seen = {}
    for i, m in enumerate(allm):
        res = m['res']
        if id(res) in seen:
            continue
        seen[id(res)] = i + 1
        sp = res['sp']
        nums = [t for t in res['txt'] if t['t'].isdigit() and t['x'] < res['X0'] + sp * 6 and t['y'] < res['staves'][0]['lines'][0]]
        # juntar digitos contiguos
        nums.sort(key=lambda t: t['x'])
        s = ''.join(t['t'] for t in nums)
        if verbose and s and s != str(i + 1):
            print('sistema en compas propio', i + 1, 'impreso', s)
    return seen


def find_ottavas(res):
    sp = res['sp']; staves = res['staves']
    out = []
    for g in res['G']:
        if g.code not in (0xE510, 0xE514):
            continue
        sh = 12 if g.code == 0xE510 else 24
        cy = g.y - sp * 0.9  # centro aproximado del numero
        segs = sorted([l for l in res['lines'] if abs(l['top'] - l['bottom']) < 0.1 and abs(l['top'] - cy) < sp * 1.0 and l['x0'] > g.x], key=lambda l: l['x0'])
        x1 = g.x + sp * 2
        for l in segs:
            if l['x0'] - x1 < sp * 2.5:
                x1 = max(x1, l['x1'])
        # pentagrama: encima de un pentagrama (alta) o debajo (bassa)
        best, bd = None, 1e9
        for i, s in enumerate(staves):
            if g.y < s['lines'][0]:
                d = s['lines'][0] - g.y
                if d < bd:
                    bd, best = d, (i, sh)
            if g.y > s['lines'][4]:
                d = g.y - s['lines'][4]
                if d < bd:
                    bd, best = d, (i, -sh)
        if best:
            out.append((g.x, x1, best[0], best[1]))
    return out
