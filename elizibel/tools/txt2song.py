"""Compila canciones escritas a mano (texto) al JSON de la app y valida que cada compas sume exacto."""
import json, re, sys, glob, os
DUR = {'w':1920,'h.':1440,'h':960,'q.':720,'q':480,'e.':360,'e':240,'s.':180,'s':120,'t':60}
BASE = {'w':1920,'h.':960,'h':960,'q.':480,'q':480,'e.':240,'e':240,'s.':120,'s':120,'t':60}
STEP = {'C':0,'D':1,'E':2,'F':3,'G':4,'A':5,'B':6}
SEMI = [0,2,4,5,7,9,11]
def parse_pitch(p):
    m = re.fullmatch(r'([A-G])(#|b|x|bb|n)?(-?\d)', p)
    if not m: raise ValueError('nota invalida ' + p)
    alt = {'#':1,'b':-1,'x':2,'bb':-2,'n':0,None:0}[m.group(2)]
    dia = int(m.group(3)) * 7 + STEP[m.group(1)]
    midi = 12 * (int(m.group(3)) + 1) + SEMI[STEP[m.group(1)]] + alt
    return dia, alt, midi, m.group(2) is not None
def compile_song(path, outdir):
    meta = {}; voices = []
    for line in open(path, encoding='utf-8'):
        line = line.strip()
        if not line or line.startswith('#'): continue
        k, v = line.split(':', 1) if re.match(r'^[a-z]+:', line) else (None, line)
        if k in ('rh', 'lh', 'mel'): voices.append((k, v.strip()))
        elif k: meta[k] = v.strip()
    num, den = map(int, meta.get('time', '4/4').split('/'))
    mlen = 1920 * num // den
    key = int(meta.get('key', 0)); pickup = int(meta.get('pickup', 0))
    keyset_sh = [3, 0, 4, 1, 5, 2, 6][:max(0, key)]; keyset_fl = [6, 2, 5, 1, 4, 0, 3][:max(0, -key)]
    ev = []; nmeas = None
    for vname, text in voices:
        staff = 1 if vname == 'lh' else 0
        bars = [b.strip() for b in text.split('|') if b.strip()]
        t = 0
        for bi, bar in enumerate(bars):
            length = pickup if (pickup and bi == 0) else mlen
            start = t; state = {}
            for tok in bar.split():
                d, p = tok.split(':', 1)
                tie = p.endswith('~'); p = p.rstrip('~')
                dur = DUR[d]
                if p == 'r':
                    ev.append([t, dur, staff, 0, 0, 1 if '.' in d else 0, 0, 0, 1, [], BASE[d]])
                else:
                    notes = []
                    for q in p.split('+'):
                        fing = 0
                        if '/' in q: q, fs = q.split('/'); fing = int(fs)
                        dia, alt, midi, explicit = parse_pitch(q)
                        st = dia % 7
                        kalt = 1 if st in keyset_sh else (-1 if st in keyset_fl else 0)
                        cur = state.get(dia, kalt)
                        shown = 9
                        if alt != cur: shown = alt; state[dia] = alt
                        notes.append([midi, dia, alt, shown, 2 if tie else 0, 0, fing])
                    ev.append([t, dur, staff, 0, 0, 1 if '.' in d else 0, 0, 0, 0, notes, BASE[d]])
                t += dur
            if t - start != length:
                raise ValueError(f'{path} {vname} compas {bi+1}: suma {t-start} en vez de {length}')
        nmeas = len(bars) if nmeas is None else nmeas
        if len(bars) != nmeas: raise ValueError(f'{path}: {vname} tiene {len(bars)} compases y otra voz {nmeas}')
    # ligaduras: la nota siguiente igual recibe tie_in
    ev.sort(key=lambda e: (e[0], e[2]))
    for i, e in enumerate(ev):
        for n in e[9]:
            if n[4] & 2:
                for f in ev[i+1:]:
                    if f[2] == e[2] and f[0] == e[0] + e[1]:
                        for m in f[9]:
                            if m[0] == n[0]: m[4] |= 1
                        break
    measures = []; t = 0
    for i in range(nmeas):
        L = pickup if (pickup and i == 0) else mlen
        measures.append([num, den, key, t, L]); t += L
    d = dict(id=meta['id'], title=meta['title'], composer=meta.get('composer', ''), arr=meta.get('arr', ''), inst=meta['inst'],
             level=int(meta.get('level', 1)), bpm=int(meta.get('bpm', 90)), tempo=[[0, int(meta.get('bpm', 90))]],
             measures=measures, order=list(range(nmeas)), ev=ev)
    json.dump(d, open(os.path.join(outdir, meta['id'] + '.json'), 'w'), separators=(',', ':'))
    return meta, nmeas
if __name__ == '__main__':
    for f in sorted(glob.glob(sys.argv[1] + '/*.txt')):
        meta, n = compile_song(f, sys.argv[2]); print('OK', meta['id'], n, 'compases')
