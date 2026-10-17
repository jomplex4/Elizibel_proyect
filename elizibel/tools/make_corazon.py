import omr, export, json
omr.LINE_TOL = 0.35
p = '/mnt/user-data/uploads/pdf-corazon-de-nio-piano-sheet-music_compress.pdf'
m = omr.run(p, dict(staves_per_system=2))
def setev(mi, staff, fixes):
    """fixes: lista (pitch_name_list, onset, dur) en orden de x"""
    evs = sorted([e for e in m[mi]['events'] if e.staff == staff and e.kind == 'n'], key=lambda e: e.x)
    assert len(evs) == len(fixes), (mi, staff, len(evs), len(fixes))
    for e, (on, du) in zip(evs, fixes):
        e.onset = on; e.dur = du
# compas 28: el grupo del tercer tiempo empieza en 960
setev(27, 0, [(0, 360), (360, 120), (480, 120), (600, 120), (720, 120), (840, 120), (960, 360), (1320, 120), (1440, 480)])
setev(27, 1, [(0, 120), (120, 120), (240, 120), (360, 120), (480, 120), (600, 120), (720, 120), (840, 120), (960, 120), (1080, 120), (1200, 120), (1320, 120), (1440, 120), (1560, 120), (1680, 120), (1800, 120)])
# compas 78 (indice 63): acorde y arpegio en tresillos de semicorchea alternando manos
setev(63, 0, [(0, 480)] + [(720 + 80 * k, 80) for k in range(3)] + [(1200 + 80 * k, 80) for k in range(3)] + [(1680 + 80 * k, 80) for k in range(3)])
setev(63, 1, [(0, 480)] + [(480 + 80 * k, 80) for k in range(3)] + [(960 + 80 * k, 80) for k in range(3)] + [(1440 + 80 * k, 80) for k in range(3)])
# compas 79 (indice 64, 3/4): dos seisillos y negra en cada mano
six = [(80 * k, 80) for k in range(6)] + [(480 + 80 * k, 80) for k in range(6)] + [(960, 480)]
setev(64, 0, six); setev(64, 1, six)
for mi in (27, 63, 64):
    for e in m[mi]['events']:
        if e.kind == 'n' and e.dur == 80: e.tuplet = (6, 4) if mi == 64 else (3, 2)
    m[mi]['total'] = max(e.onset + e.dur for e in m[mi]['events'])
bad = [(i, x['total'], x['mlen']) for i, x in enumerate(m) if x['total'] != x['mlen']]
print('compases', len(m), 'con suma mala', bad)
meta = dict(id='corazon', title='Corazón de niño', composer='Raúl Di Blasio', arr='Faltan los compases 46 a 59 (no legibles en los PDF)', inst='piano', level=4)
meta['tempo'] = [[a, b] for a, b in export.tempos(m)] or [[0, 90]]
meta['bpm'] = meta['tempo'][0][1]
d = export.build(meta, [(m, [0, 1], 0)], 'songs/corazon.json')
r = [n[0] for e in d['ev'] for n in e[9]]
print('notas', len(r), 'rango', min(r), max(r), 'tempo', meta['tempo'][:4])
