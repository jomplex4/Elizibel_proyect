"""Corazon de nino (PDF con plicas y haces como imagen): glifos exactos + plicas/haces/lineas reconstruidos del raster."""
import pdfplumber, numpy as np, cv2, omr, collections
S = 4.0  # 288 dpi
MAP = {0x74: 0xE0A4, 0x7C: 0xE0A3, 0xAC: 0xE0A2, 0x22: 0xE260, 0x21: 0xE262, 0x23: 0xE261, 0xC7: 0xE1E7,
       0x63: 0xE4E5, 0x64: 0xE4E6, 0x65: 0xE4E7, 0xE000: 0xE050, 0xE001: 0xE062, 0x34: 0xE084, 0x32: 0xE082, 0x33: 0xE083,
       0xB4: 0xE510, 0xFE: 0xE510}

class Page:
    pass

def build(pg, pageno=None):
    H = pg.height
    P = Page(); P.height = H; P.width = pg.width
    seen = set(); chars = []
    for c in pg.chars:
        if not c['text']: continue
        cp = ord(c['text'][0]); f = c['fontname'].split('+')[-1]
        x = c['matrix'][4]; y = c['matrix'][5]
        k = (cp, round(x, 1), round(y, 1))
        if k in seen: continue
        seen.add(k)
        if f == 'font2' and cp in MAP:
            sz = c['size']
            code = MAP[cp]
            if cp == 0xFE and sz > 30: continue
            d = dict(c); d['text'] = chr(code); d['fontname'] = 'Leland'
            d['size'] = 16.4 if cp not in (0xB4, 0xFE) else -12  # tamano normal de cabeza
            if cp == 0x74 and sz < 30: d['size'] = 10  # notas de adorno
            if cp in (0xE000, 0xE001) and sz < 30: d['size'] = 16.4
            chars.append(d)
        elif f not in ('font2', 'font1'):
            chars.append(c)
    P.chars = chars
    from PIL import Image
    im = np.array(Image.open('/tmp/work/czr/p-%d.pgm' % (pageno + 1)).convert('L'))
    b = (im < 140).astype(np.uint8)
    P.raster = b
    # lineas horizontales finas (pentagramas, adicionales, guiones de 8va)
    hk = cv2.getStructuringElement(cv2.MORPH_RECT, (int(6 * S), 1))
    b2 = (im < 215).astype(np.uint8)   # umbral claro: las lineas finas salen grises
    hl = cv2.morphologyEx(b2, cv2.MORPH_OPEN, hk)
    n, lab, st, cen = cv2.connectedComponentsWithStats(hl)
    lines = []
    # pentagramas por proyeccion de filas: filas con tinta en mas de la mitad del ancho
    rows = hl.sum(1)
    W = b.shape[1]
    cand = np.where(rows > W * 0.45)[0]
    grp = []
    for y in cand:
        if grp and y - grp[-1][-1] <= 1: grp[-1].append(y)
        else: grp.append([y])
    for g in grp:
        cov = rows[g[0]:g[-1] + 1]
        core = [g[0] + i for i, v in enumerate(cov) if v >= cov.max() * 0.93]
        r = hl[core[0]:core[-1] + 1].max(0)
        xs = np.where(r)[0]
        yy = (core[0] + core[-1] + 1) / 2 / S
        lines.append(dict(x0=xs[0] / S, x1=xs[-1] / S, top=yy, bottom=yy, linewidth=len(g) / S))
    for j in range(1, n):
        x, y, w, h, a = st[j]
        if h <= 4 and 6 * S <= w < W * 0.4:
            yy = (y + h / 2) / S
            lines.append(dict(x0=x / S, x1=(x + w) / S, top=yy, bottom=yy, linewidth=h / S))
    # plicas y barras: verticales finas y altas
    vk = cv2.getStructuringElement(cv2.MORPH_RECT, (1, int(9 * S)))
    vl = cv2.morphologyEx(b, cv2.MORPH_OPEN, vk)
    n, lab, st, cen = cv2.connectedComponentsWithStats(vl)
    for j in range(1, n):
        x, y, w, h, a = st[j]
        if w <= 7 and h >= 9 * S:
            xx = (x + w / 2) / S
            lines.append(dict(x0=xx, x1=xx, top=y / S, bottom=(y + h) / S, linewidth=w / S))
    # los pentagramas se reconstruyen desde las claves del inicio de cada sistema (posicion exacta de los glifos):
    # en este PDF algunas lineas quedan tapadas por capas de imagen
    lines = [l for l in lines if not (l['top'] == l['bottom'] and l['x1'] - l['x0'] > W / S * 0.4)]
    sp = 5.0
    for c in chars:
        cp = ord(c['text'][0])
        if cp in (0xE050, 0xE062) and c['matrix'][4] < 75:
            y = H - c['matrix'][5]
            top = y - 3 * sp if cp == 0xE050 else y - sp
            for k in range(5):
                lines.append(dict(x0=54.0, x1=706.0, top=top + k * sp, bottom=top + k * sp, linewidth=0.6))
    P.lines = lines
    # haces: bandas gruesas (sin lineas finas ni plicas)
    bk = cv2.getStructuringElement(cv2.MORPH_RECT, (int(3.5 * S), int(1.3 * S)))
    bm = cv2.morphologyEx(b, cv2.MORPH_OPEN, bk)
    n, lab, st, cen = cv2.connectedComponentsWithStats(bm)
    curves = []
    for j in range(1, n):
        x, y, w, h, a = st[j]
        if w < 4 * S or a < w * 1.2 * S: continue
        comp = (lab[y:y + h, x:x + w] == j)
        cols = np.where(comp.any(0))[0]
        l, r = cols[0], cols[-1]
        def edge(cx):
            ys = np.where(comp[:, cx])[0]; return ys[0], ys[-1]
        lt, lb = edge(l); rt, rb = edge(r)
        if (lb - lt) > 3.2 * S or (rb - rt) > 3.2 * S: continue  # cabezas de nota u otros bloques
        pts = [((x + l) / S, (y + lt) / S), ((x + r) / S, (y + rt) / S), ((x + r) / S, (y + rb) / S), ((x + l) / S, (y + lb) / S)]
        curves.append(dict(object_type='curve', path=[('m', pts[0])] + [('l', p) for p in pts[1:]] + [('h',)], pts=pts + [pts[0]], fill=True,
                           top=min(p[1] for p in pts), bottom=max(p[1] for p in pts), x0=min(p[0] for p in pts), x1=max(p[0] for p in pts)))
    P.curves = curves
    P.rects = []
    return P


def run():
    pdf = pdfplumber.open('/mnt/user-data/uploads/Corazón_de_niño.pdf')
    state = dict(clef={}, key={}, time=(4, 4))
    allm = []
    for pi, pg in enumerate(pdf.pages):
        P = build(pg, pi)
        for res in omr.analyze_page(P, dict(staves_per_system=2), None):
            res['ottavas'] = omr.find_ottavas(res)
            ms = omr.finish_system(res, {}, state)
            for m in ms:
                m['page'] = pi; m['res'] = res; m['P'] = P
            allm.extend(ms)
    return allm
