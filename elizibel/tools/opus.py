import pdfplumber, omr
MAP = {'œ': 0xE0A4, '˙': 0xE0A3, 'w': 0xE0A2, 'b': 0xE260, 'n': 0xE261, '#': 0xE262, '&': 0xE050, '?': 0xE062,
       '™': 0xE1E7, 'j': 0xE240, 'J': 0xE241, 'Œ': 0xE4E5, '‰': 0xE4E6, 'Ó': 0xE4E4, '∑': 0xE4E3, '2': 0xE082, '3': 0xE083, '4': 0xE084}
class P: pass
def wrap(pg):
    p = P(); p.height = pg.height; p.width = pg.width
    ch = []
    for c in pg.chars:
        f = c['fontname']
        if ('OpusStd' in f or 'OpusSpecialStd' in f) and c['text'] in MAP:
            d = dict(c); d['text'] = chr(MAP[c['text']]); d['fontname'] = 'Leland'; ch.append(d)
        elif 'Opus' not in f:
            ch.append(c)
    p.chars = ch; p.lines = pg.lines; p.curves = pg.curves; p.rects = pg.rects
    return p
def run(path, n=1, time=(4, 4)):
    pdf = pdfplumber.open(path)
    state = dict(clef={}, key={}, time=time); allm = []
    for pi, pg in enumerate(pdf.pages):
        pw = wrap(pg)
        for res in omr.analyze_page(pw, dict(staves_per_system=n), None):
            res['ottavas'] = omr.find_ottavas(res)
            ms = omr.finish_system(res, {}, state)
            omr.find_ties(res, ms)
            for m in ms: m['page'] = pi; m['res'] = res
            allm.extend(ms)
    return allm
