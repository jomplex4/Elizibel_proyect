import mido, json
from midi2song import track_notes, to_events, pieces, STD
m = mido.MidiFile('/mnt/user-data/uploads/opensheets-listen-to-your-heart.mid')
scale = 480 / m.ticks_per_beat
ML = 1920
mel = track_notes(m.tracks[7], scale)
prh = track_notes(m.tracks[1], scale)
plh = track_notes(m.tracks[0], scale)
melm = set(s // ML for s, d, n in mel)
# mano derecha: la melodia de la voz donde hay voz; donde no, la mano derecha del piano de la cancion
rh = [x for x in mel] + [x for x in prh if x[0] // ML not in melm]
# la melodia no debe solaparse consigo misma (una sola voz): se recorta cada nota hasta la siguiente
rh.sort()
clean = []
for i, (s, d, n) in enumerate(rh):
    nxt = [x[0] for x in rh[i + 1:] if x[0] > s]
    if nxt and s + d > nxt[0] and (s // ML) in melm:
        d = nxt[0] - s
    clean.append((s, d, n))
# version original (melodia de la voz + piano de la cancion) con espacios mas cortos:
# en la melodia, un hueco de hasta un tiempo se llena alargando la nota anterior. La izquierda queda como esta.
def legato(notes):
    notes = sorted(notes); out = []
    starts = sorted(set(s for s, d, n in notes))
    for s, d, n in notes:
        nxt = [x for x in starts if x > s]
        if nxt:
            gap = nxt[0] - (s + d)
            if 0 < gap <= 480: d = nxt[0] - s
        out.append((s, d, n))
    return out
clean = legato(clean)
ev = to_events(clean, 0, 0, ML) + to_events(plh, 1, 0, ML)
end = max(e[0] + e[1] for e in ev)
nm = (end + ML - 1) // ML
# silencios donde no suena nada en cada pentagrama
for staff in (0, 1):
    occ = sorted((e[0], e[0] + e[1]) for e in ev if e[2] == staff and not e[8])
    t = 0; gaps = []
    for a, b in occ:
        if a > t: gaps.append((t, a))
        t = max(t, b)
    if t < nm * ML: gaps.append((t, nm * ML))
    for a, b in gaps:
        for (tt, dd) in pieces(a, b - a, ML):
            base = {3840:3840,2880:1920,1920:1920,1440:960,960:960,720:480,480:480,360:240,240:240,180:120,120:120,60:60}[dd]
            ev.append([tt, dd, staff, 0, 0, 1 if base != dd else 0, 0, 0, 1, [], base])
meas = [[4, 4, 2, i * ML, ML] for i in range(nm)]
keyset = [3, 0]
state = {}
for e in sorted(ev, key=lambda e: e[0]):
    mi = e[0] // ML
    for n in e[9]:
        k = (e[2], mi, n[1])
        cur = state.get(k, 1 if n[1] % 7 in keyset else 0)
        if n[2] != cur and not (n[4] & 1):
            n[3] = n[2]; state[k] = n[2]
d = dict(id='listen', title='Listen to Your Heart', composer='Roxette', arr='Piano solo: melodía en la derecha, acordes en la izquierda', inst='piano', level=3,
         bpm=115, tempo=[[0, 115]], measures=meas, order=list(range(nm)), ev=sorted(ev, key=lambda e: (e[0], e[2])))
json.dump(d, open('songs/listen.json', 'w'), separators=(',', ':'))
r = [n[0] for e in ev for n in e[9]]
print('compases', nm, 'notas', len(r), 'rango', min(r), max(r), 'compases con melodia', len(melm), 'silencios', sum(1 for e in ev if e[8]))
