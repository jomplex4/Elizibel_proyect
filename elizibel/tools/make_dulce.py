import opus, omr, export, json, bisect
p = '/mnt/user-data/uploads/pdf-dulce-navidad-partitura-completa_compress.pdf'
m = opus.run(p)
assert not omr.check(m, verbose=False)
base = dict(composer='Villancico (melodía de Jingle Bells)', level=1, bpm=112, tempo=[[0, 112]])
d = export.build(dict(base, id='p_dulce', title='Dulce Navidad', arr='Melodía para mano derecha', inst='piano'), [(m, [0], 0)], 'songs/p_dulce.json')
f = export.build(dict(base, id='f_dulce', title='Dulce Navidad', arr='En Sol, como tu hoja de flauta', inst='flute'), [(m, [0], 0)], 'songs/f_dulce.json')
SEMI = [0, 2, 4, 5, 7, 9, 11]
key = 1
for mm in f['measures']: mm[2] = key
starts = [mm[3] for mm in f['measures']]
state = {}; cur = -1
for e in sorted(f['ev'], key=lambda e: e[0]):
    mi = bisect.bisect_right(starts, e[0]) - 1
    if mi != cur: state = {}; cur = mi
    for n in e[9]:
        midi = n[0] + 2; dia = n[1] + 1
        alt = midi - (12 * (dia // 7 + 1) + SEMI[dia % 7])
        kalt = 1 if dia % 7 == 3 else 0
        if alt != state.get(dia, kalt) and not (n[4] & 1):
            n[3] = alt; state[dia] = alt
        else:
            n[3] = 9
        n[0], n[1], n[2] = midi, dia, alt
json.dump(f, open('songs/f_dulce.json', 'w'), separators=(',', ':'))
for x in (d, f):
    r = [n[0] for e in x['ev'] for n in e[9]]
    print(x['id'], len(x['measures']), 'notas', len(r), 'rango', min(r), max(r))
