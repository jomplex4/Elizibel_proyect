import json, bisect
SEMI = [0, 2, 4, 5, 7, 9, 11]
KEYSETS = {k: ([3, 0, 4, 1, 5, 2, 6][:max(0, k)], [6, 2, 5, 1, 4, 0, 3][:max(0, -k)]) for k in range(-7, 8)}
def transpose(d, semis, steps, key):
    for mm in d['measures']: mm[2] = key
    sh, fl = KEYSETS[key]
    starts = [mm[3] for mm in d['measures']]
    state = {}; cur = -1
    for e in sorted(d['ev'], key=lambda e: e[0]):
        mi = bisect.bisect_right(starts, e[0]) - 1
        if mi != cur: state = {}; cur = mi
        for n in e[9]:
            midi = n[0] + semis; dia = n[1] + steps
            alt = midi - (12 * (dia // 7 + 1) + SEMI[dia % 7])
            kalt = 1 if dia % 7 in sh else (-1 if dia % 7 in fl else 0)
            if alt != state.get(dia, kalt) and not (n[4] & 1): n[3] = alt; state[dia] = alt
            else: n[3] = 9
            n[0], n[1], n[2] = midi, dia, alt
    return d
