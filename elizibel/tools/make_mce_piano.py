import omr, export, fix_ties
omr.LINE_TOL = 0.35
m = omr.run('mce_piano.pdf', dict(staves_per_system=2))
assert not omr.check(m, verbose=False)
meta = dict(id='mce_p', title='Mi corazón encantado', composer='Tetsuro Oda', arr='Tema de Dragon Ball GT', inst='piano', level=2)
meta['tempo'] = [[a, b] for a, b in export.tempos(m)] or [[0, 90]]
meta['bpm'] = meta['tempo'][0][1]
d = export.build(meta, [(m, [0, 1], 0)], 'songs/mce_p.json')
print('ligaduras', fix_ties.fix('songs/mce_p.json'))
r = [n[0] for e in d['ev'] for n in e[9]]
print('compases', len(m), 'orden', len(d['order']), 'notas', len(r), 'rango', min(r), max(r), 'tempo', meta['tempo'])
