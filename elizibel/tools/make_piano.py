import omr, export
U='/mnt/user-data/uploads/'
def go(f, meta, n=2, parts=None):
    m = omr.run(U + f, dict(staves_per_system=n))
    p = omr.check(m, verbose=False)
    meta['tempo'] = [[a, b] for a, b in export.tempos(m)]
    meta['bpm'] = meta['tempo'][0][1] if meta['tempo'] else meta.get('bpm', 90)
    d = export.build(meta, [(m, s, i) for i, s in enumerate(parts or [[0, 1]])], 'songs/%s.json' % meta['id'])
    nn = sum(len(e[9]) for e in d['ev'])
    print(meta['id'], 'compases', len(m), 'notas', nn, 'problemas', p)
go('pdf-icarus-tony-ann_compress.pdf', dict(id='icarus', title='Icarus', composer='Tony Ann', arr='Arr. Dave', inst='piano', level=4))
go('pdf-paul-de-senneville-mariage-damour_compress.pdf', dict(id='mariage', title="Mariage d'Amour", composer='Paul de Senneville', arr='Arr. Toms Mucenieks', inst='piano', level=3))
go('pdf-p-classtruncatedtext-module-lineclamped-85ulhh-style-max-lines5interstellar-p_compress.pdf', dict(id='interstellar', title='Interstellar', composer='Hans Zimmer', arr='Main Theme · Arr. Gabriel Piano', inst='piano', level=5))
go('pdf-einaudix27s-experience-piano-sheet-music_compress.pdf', dict(id='experience', title='Experience', composer='Ludovico Einaudi', arr='Dos pianos', inst='piano', level=4, partNames=['Piano 1', 'Piano 2']), n=4, parts=[[0, 1], [2, 3]])
go('pdf-someone-you-loved-piano-sheet-music_compress.pdf', dict(id='someone', title='Someone You Loved', composer='Lewis Capaldi', arr='Acompañamiento · Arr. F. Pastori', inst='piano', level=2))
