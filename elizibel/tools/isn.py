"""Interstellar (Patrik Pietschmann): el PDF dibuja cada simbolo como trazo. Se clasifican los trazos por forma
y tamano y se convierten en glifos SMuFL con su posicion exacta; plicas, haces y lineas son vectores reales."""
import pdfplumber, omr, collections
PDF = '/mnt/user-data/uploads/pdf-interstellar_compress.pdf'
class P: pass
def sig(c):
    return ''.join(o[0] for o in c['path']), c['x1'] - c['x0'], c['bottom'] - c['top']
def glyph(code, x, y, size=16.4):
    return dict(text=chr(code), fontname='Leland', matrix=(1, 0, 0, 1, x, None), x0=x, x1=x + 5, size=size, top=y, _y=y)
def wrap(pg):
    H = pg.height
    p = P(); p.height = H; p.width = pg.width
    chars = [c for c in pg.chars]
    heads, holes, dots, clefsG, clefsF, flags, rests, ott = [], [], [], [], [], [], [], []
    other = []
    for c in pg.curves:
        ops, w, h = sig(c)
        cy = (c['top'] + c['bottom']) / 2
        if ops == 'mcccch' and 4.6 <= w <= 7.5 and 3.6 <= h <= 5.2: heads.append(c)
        elif ops == 'mcccccch' and 3.2 <= w <= 5.5 and 2.0 <= h <= 4.2: holes.append(c)
        elif ops == 'mcccch' and w < 2.6 and h < 2.6: dots.append(c)
        elif ops.startswith('mlccccc') and 10 < w < 14 and 28 < h < 34: clefsG.append(c)
        elif ops.startswith('mccccccccc') and 8 < w < 11 and 14 < h < 18: clefsF.append(c)
        elif ops.startswith('mcccccclccccc') and w < 7 and 11 < h < 17: flags.append(c)
        elif ops == 'mclclclclh': rests.append(c)
        elif ops == 'mcccccccccccch' and 6 < w < 8.5 and 7 < h < 9: ott.append((0xE510, c))
        elif ops.startswith('mlccccccccccclcc') and 6 < w < 8.5 and 7 < h < 9: ott.append((0xE514, c))
        else: other.append(c)
    g = []
    for c in heads:
        hollow = any(hc['x0'] > c['x0'] - 0.5 and hc['x1'] < c['x1'] + 0.5 and hc['top'] > c['top'] - 0.5 and hc['bottom'] < c['bottom'] + 0.5 for hc in holes)
        w = c['x1'] - c['x0']
        code = 0xE0A4 if not hollow else (0xE0A2 if w > 6.2 else 0xE0A3)
        g.append(glyph(code, c['x0'], (c['top'] + c['bottom']) / 2))
    for c in clefsG: g.append(glyph(0xE050, c['x0'], (c['top'] + c['bottom']) / 2 + 3))
    for c in clefsF: g.append(glyph(0xE062, c['x0'], (c['top'] + c['bottom']) / 2 - 2))
    for c in dots:
        cx = (c['x0'] + c['x1']) / 2; cy = (c['top'] + c['bottom']) / 2
        if any(f['x1'] - 1 <= cx <= f['x1'] + 6 and f['top'] - 2 <= cy <= f['bottom'] + 2 for f in clefsF): continue
        g.append(glyph(0xE1E7, c['x0'], cy))
    for c in flags:
        g.append(glyph(0xE240, c['x0'], c['top'])); g.append(glyph(0xE241, c['x0'], c['bottom']))
    for code, c in ott: g.append(glyph(code, c['x0'], c['bottom']))
    p.rests_raw = rests
    p.chars = chars + g
    p.lines = pg.lines; p.rects = pg.rects
    p.curves = [c for c in pg.curves if c not in heads and c not in holes]
    # arcos finos (ligaduras): se resumen en sus dos extremos para el detector de ligaduras
    for c in pg.curves:
        ops, w, h = sig(c)
        if ops.startswith('mv') and w > 4:
            a = c['path'][0][1]; b = c['path'][1][-1]
            if b[0] < a[0]: a, b = b, a
            p.curves.append(dict(object_type='curve', path=[('m', a), ('c', a, b, b)], pts=[a, b], fill=False,
                                 top=c['top'], bottom=c['bottom'], x0=c['x0'], x1=c['x1']))
    p.other = other
    return p

def run():
    pdf = pdfplumber.open(PDF)
    state = dict(clef={}, key={}, time=(3, 4)); allm = []
    for pi, pg in enumerate(pdf.pages):
        pw = wrap(pg)
        for res in omr.analyze_page(pw, dict(staves_per_system=2), None):
            sp = res['sp']
            # silencios de bloque: cuelga de la 4a linea = redonda; sentado en la 3a = blanca
            for c in pw.rests_raw:
                for si, st in enumerate(res['staves']):
                    if st['lines'][0] - sp < c['top'] < st['lines'][4] + sp:
                        ev = omr.Event('r', c['x0'], si)
                        hang = any(abs(c['top'] - y) < 0.6 for y in st['lines'])
                        ev.base = 4 * omr.Q if hang else 2 * omr.Q
                        ev.rest_y = (c['top'] + c['bottom']) / 2; ev.rest_code = 0; ev.heads = []
                        if res['X0'] < c['x0'] < res['X1']: res['raw_events'].append(ev)
            # en esta partitura todas las marcas son 8va / 15ma hacia arriba: afectan al pentagrama de abajo de la marca
            ott = []
            for (x0, x1, si, shv) in omr.find_ottavas(res):
                g_y = None
                for g in res['G']:
                    if g.code in (0xE510, 0xE514) and abs(g.x - x0) < 0.5: g_y = g.y
                below = [k for k, st in enumerate(res['staves']) if g_y is not None and st['lines'][0] > g_y]
                k = below[0] if below else si
                ott.append((x0, x1, k, abs(shv)))
            res['ottavas'] = ott
            ms = omr.finish_system(res, {}, state)
            omr.find_ties(res, ms)
            for m in ms: m['page'] = pi; m['res'] = res
            allm.extend(ms)
    return allm
