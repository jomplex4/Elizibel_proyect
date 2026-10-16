import mido, json
SH = [(0,0),(0,1),(1,0),(1,1),(2,0),(3,0),(3,1),(4,0),(4,1),(5,0),(5,1),(6,0)]
STD = [3840,2880,1920,1440,960,720,480,360,240,180,120,60]
def spell(midi):
    oc = midi // 12 - 1; s, a = SH[midi % 12]
    return oc * 7 + s, a
def pieces(t0, dur, mlen):
    """divide una duracion en figuras estandar sin cruzar barras ni medio compas"""
    out = []; t = t0; rem = dur
    while rem > 0:
        bar_end = (t // mlen + 1) * mlen
        lim = min(rem, bar_end - t)
        for s in STD:
            if s <= lim and (t % min(s, 960) == 0 or s < 240):
                break
        else:
            s = 60
        out.append((t, s)); t += s; rem -= s
    return out
def track_notes(tr, scale):
    t = 0; on = {}; ns = []
    for x in tr:
        t += x.time
        if x.type == 'note_on' and x.velocity > 0:
            on[x.note] = t
        elif x.type in ('note_off', 'note_on') and x.note in on:
            s = on.pop(x.note); ns.append((s * scale, (t - s) * scale, x.note))
    # cuantizar a semicorchea de 120 ticks (480 por negra)
    q = []
    for s, d, n in ns:
        s2 = round(s / 60) * 60; d2 = max(60, round(d / 60) * 60)
        q.append((s2, d2, n))
    return q
def to_events(notes, staff, part, mlen, key_sharps=2):
    ev = []
    groups = {}
    for s, d, n in notes:
        groups.setdefault((s, d), []).append(n)
    for (s, d), ns in sorted(groups.items()):
        ps = pieces(s, d, mlen)
        for i, (t, dd) in enumerate(ps):
            nl = []
            for n in sorted(ns):
                dia, alt = spell(n)
                tie = (1 if i > 0 else 0) | (2 if i < len(ps) - 1 else 0)
                nl.append([n, dia, alt, 9, tie, 0])
            base = {3840:3840,2880:1920,1920:1920,1440:960,960:960,720:480,480:480,360:240,240:240,180:120,120:120,60:60}[dd]
            dots = 1 if base != dd else 0
            stem = 1 if staff == 0 and nl[0][0] < 71 or staff == 1 and nl[0][0] < 50 else 2
            ev.append([t, dd, staff, stem, 0, dots, 0, part, 0, nl, base])
    return ev
def build(path, meta, rh, lh, acc, out, flute_acc=None):
    m = mido.MidiFile(path); scale = 480 / m.ticks_per_beat
    mlen = 1920
    ev = []
    ev += to_events(track_notes(m.tracks[rh], scale), 0, 0, mlen)
    ev += to_events(track_notes(m.tracks[lh], scale), 1, 0, mlen)
    for k in acc:
        ev += to_events(track_notes(m.tracks[k], scale), 0, 1, mlen)
    end = max(e[0] + e[1] for e in ev)
    nm = (end + mlen - 1) // mlen
    meas = [[4, 4, meta.get('key', 0), i * mlen, mlen] for i in range(nm)]
    # mostrar alteraciones segun armadura y compas
    ks = meta.get('key', 0)
    kalt = {3: 1, 0: 1, 4: 1, 1: 1, 5: 1, 2: 1, 6: 1}
    keyset = [3, 0, 4, 1, 5, 2, 6][:ks]
    state = {}
    for e in sorted(ev, key=lambda e: e[0]):
        mi = e[0] // mlen
        for n in e[9]:
            k = (e[7], e[2], mi, n[1])
            cur = state.get(k, 1 if n[1] % 7 in keyset else 0)
            if n[2] != cur and not (n[4] & 1):
                n[3] = n[2]; state[k] = n[2]
    d = dict(meta); d['measures'] = meas; d['order'] = list(range(nm)); d['ev'] = sorted(ev, key=lambda e: (e[0], e[2]))
    json.dump(d, open(out, 'w'), separators=(',', ':'))
    print(meta['id'], 'compases', nm, 'eventos', len(ev))
if __name__ == '__main__':
    build('/mnt/user-data/uploads/opensheets-listen-to-your-heart.mid',
          dict(id='listen', title='Listen to Your Heart', composer='Roxette', arr='Piano de la canción · banda de fondo', inst='piano', level=3, key=2, bpm=115, tempo=[[0, 115], [20, 60]], partNames=['Piano', 'Banda']),
          1, 0, [7, 5], 'songs/listen.json')
