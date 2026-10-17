import isn, omr, export
m = isn.run()
assert not omr.check(m, verbose=False)
fl = [export.repeats(x) for x in m]
meta = dict(id='interstellar', title='Interstellar', composer='Hans Zimmer', arr='Cornfield Chase (Main Theme) · Arr. Patrik Pietschmann', inst='piano', level=4)
meta['tempo'] = [[a, b] for a, b in export.tempos(m)] or [[0, 100]]
meta['bpm'] = meta['tempo'][0][1]
d = export.build(meta, [(m, [0, 1], 0)], 'songs/interstellar.json')
r = [n[0] for e in d['ev'] for n in e[9]]
print('compases', len(m), 'orden', len(d['order']), 'notas', len(r), 'rango', min(r), max(r), 'tempo', meta['tempo'])
# ligaduras que cruzan de un sistema al siguiente: la nota igual que empieza justo despues recibe la llegada
import json
d = json.load(open('songs/interstellar.json'))
ev = d['ev']; idx = {}
for e in ev: idx.setdefault((e[0], e[2], e[7]), []).append(e)
fix = 0
for e in ev:
    for n in e[9]:
        if n[4] & 2:
            for f in idx.get((e[0] + e[1], e[2], e[7]), []):
                for q in f[9]:
                    if q[0] == n[0] and not (q[4] & 1): q[4] |= 1; fix += 1
json.dump(d, open('songs/interstellar.json', 'w'), separators=(',', ':'))
print('ligaduras completadas', fix, 'salidas', sum(1 for e in ev for n in e[9] if n[4] & 2), 'llegadas', sum(1 for e in ev for n in e[9] if n[4] & 1))
