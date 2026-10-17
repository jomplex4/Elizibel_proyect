import numpy as np, cv2, json
from PIL import Image, ImageDraw
NAMES = "CDEFGAB"
def analyze(png):
    im = np.array(Image.open(png).convert('L')); H, W = im.shape
    b = (im < 150).astype(np.uint8)
    rows = (cv2.morphologyEx(b, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (60, 1)))).sum(1)
    cand = np.where(rows > W * 0.35)[0]
    grp = []
    for y in cand:
        if grp and y - grp[-1][-1] <= 2: grp[-1].append(y)
        else: grp.append([y])
    ly = [np.mean(g) for g in grp]
    staves = []; i = 0
    while i + 4 < len(ly):
        d = np.diff(ly[i:i + 5])
        if d.std() < 2 and 18 < d.mean() < 40: staves.append(ly[i:i + 5]); i += 5
        else: i += 1
    out = []
    for st in staves:
        sp = (st[4] - st[0]) / 4
        y0 = int(st[0] - sp * 6); y1 = int(st[4] + sp * 5)
        sub = b[y0:y1].copy()
        # borrar lineas del pentagrama (solo donde no hay tinta arriba/abajo)
        for y in st:
            yy = int(round(y)) - y0
            for k in range(-2, 3):
                r = yy + k
                if 0 <= r < sub.shape[0] and 0 <= r - 3 and r + 3 < sub.shape[0]:
                    sub[r] = sub[r] & (sub[r - 3] | sub[r + 3])
        # lineas verticales largas = barras de compas (cruzan el pentagrama completo)
        colsum = b[int(st[0]):int(st[4])].sum(0)
        barx = np.where(colsum >= (st[4] - st[0]) * 0.97)[0]
        bars = []
        for x in barx:
            if bars and x - bars[-1][-1] <= 3: bars[-1].append(x)
            else: bars.append([x])
        bars = [int(np.mean(g)) for g in bars]
        # cabezas rellenas: apertura con elipse
        k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (int(sp * 0.8), int(sp * 0.62)))
        filled = cv2.morphologyEx(sub, cv2.MORPH_OPEN, k)
        # huecas: rellenar agujeros pequenos
        cnts, hier = cv2.findContours(sub, cv2.RETR_CCOMP, cv2.CHAIN_APPROX_SIMPLE)
        holes = np.zeros_like(sub)
        for ci, c in enumerate(cnts):
            if hier[0][ci][3] >= 0:
                x, y, w, h = cv2.boundingRect(c)
                if sp * 0.2 < w < sp * 1.3 and sp * 0.12 < h < sp * 0.8: cv2.drawContours(holes, cnts, ci, 1, -1)
        hollow_fill = cv2.morphologyEx(cv2.bitwise_or(sub, holes), cv2.MORPH_OPEN, k)
        heads = []
        n, lab, stt, cen = cv2.connectedComponentsWithStats(filled)
        for j in range(1, n):
            x, y, w, h, a = stt[j]
            if w > sp * 2.4 or h > sp * 1.8: continue
            heads.append([cen[j][0], cen[j][1] + y0, 'f'])
        n, lab, stt, cen = cv2.connectedComponentsWithStats(hollow_fill)
        for j in range(1, n):
            x, y, w, h, a = stt[j]
            if w > sp * 2.4 or h > sp * 1.8 or w < sp * 0.7: continue
            if any(abs(cen[j][0] - hx) < sp * 0.6 and abs(cen[j][1] + y0 - hy) < sp * 0.6 for hx, hy, _ in heads): continue
            heads.append([cen[j][0], cen[j][1] + y0, 'o'])
        heads.sort()
        res = []
        for x, y, t in heads:
            pos = round((st[4] - y) / (sp / 2)); d = 30 + pos
            res.append((float(x), NAMES[d % 7] + str(d // 7), t, int(pos)))
        out.append(dict(st=st, sp=sp, bars=bars, heads=res))
    return out, im
def draw(png, out, im, sysidx, path):
    s = out[sysidx]; st = s['st']; sp = s['sp']
    y0 = int(st[0] - sp * 7); y1 = int(st[4] + sp * 7)
    c = Image.fromarray(im[y0:y1]).convert('RGB'); d = ImageDraw.Draw(c)
    for bx in s['bars']: d.line((bx, 0, bx, 40), fill=(255, 0, 255), width=3)
    for i, (x, nm, t, pos) in enumerate(s['heads']):
        d.text((x - 8, int(st[0] - sp * 6.2 - y0) + (i % 2) * 14), nm + ('o' if t == 'o' else ''), fill=(220, 0, 0))
    return c
if __name__ == '__main__':
    for p in (1, 2):
        out, im = analyze('mce/p-%d.png' % p)
        print('pagina', p, 'sistemas', len(out))
        for i, s in enumerate(out):
            print(i + 1, 'barras', s['bars'], 'notas', len(s['heads']))
