import omr, export, json
omr.EXTRA_FONTS.append('CIDFont+F1'); omr.LINE_TOL = 0.45
p = '/mnt/user-data/uploads/pdf-interstellar-score_compress.pdf'
m = omr.run(p, dict(staves_per_system=4))
assert not omr.check(m, verbose=False)
fl = [export.repeats(x) for x in m]
print('repeticiones', [(i + 1, a, b) for i, (a, b) in enumerate(fl) if a or b], 'orden', len(export.expand(fl)))
d = export.build(dict(id='interstellar', title='Interstellar', composer='Hans Zimmer', arr='Main Theme · Arr. Cristian Ferrer Díaz', inst='piano', level=2, bpm=90, tempo=[[0, 90]]), [(m, [2, 3], 0)], 'songs/interstellar.json')
f = export.build(dict(id='interstellar_fl', title='Interstellar', composer='Hans Zimmer', arr='Main Theme · melodía de Cristian Ferrer Díaz', inst='flute', level=2, bpm=90, tempo=[[0, 90]]), [(m, [0], 0)], 'songs/interstellar_fl.json')
for x in (d, f):
    r = [n[0] for e in x['ev'] for n in e[9]]
    print(x['id'], len(x['measures']), 'notas', len(r), 'rango', min(r), max(r), 'acordes', sum(1 for e in x['ev'] if len(e[9]) > 1))
# la flauta dulce soprano llega a Re6 escrito: se baja un tono completo (La menor -> Sol menor)
SEMI = [0, 2, 4, 5, 7, 9, 11]
up = sum(1 for e in f['ev'] for n in e[9] if n[0] > 86)
print('notas sobre Re6 antes de transportar:', up)
key = -2
for mm in f['measures']: mm[2] = key
state = {}
cur_m = -1
f['ev'].sort(key=lambda e: e[0])
mstarts = [mm[3] for mm in f['measures']]
import bisect
for e in f['ev']:
    if len(e[9]) > 1: e[9] = [max(e[9], key=lambda n: n[0])]
    mi = bisect.bisect_right(mstarts, e[0]) - 1
    if mi != cur_m: state = {}; cur_m = mi
    for n in e[9]:
        midi = n[0] - 2; dia = n[1] - 1
        nat = 12 * (dia // 7 + 1) + SEMI[dia % 7]
        alt = midi - nat
        kalt = -1 if dia % 7 in (6, 2) else 0
        curv = state.get(dia, kalt)
        shown = 9
        if alt != curv and not (n[4] & 1): shown = alt; state[dia] = alt
        n[0], n[1], n[2], n[3] = midi, dia, alt, shown
r = [n[0] for e in f['ev'] for n in e[9]]
print('flauta transportada rango', min(r), max(r))
f['arr'] = 'Main Theme · un tono abajo para la flauta dulce'
json.dump(f, open('songs/interstellar_fl.json', 'w'), separators=(',', ':'))
