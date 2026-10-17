"""Repara ligaduras huerfanas: una llegada sin salida deja la nota muda en la app."""
import json, sys
def fix(path):
    d = json.load(open(path)); ev = sorted(d['ev'], key=lambda e: (e[0], e[2]))
    fixed = dropped = 0
    for e in ev:
        for n in e[9]:
            if n[4] & 1:
                prev = [f for f in ev if f[2] == e[2] and f[7] == e[7] and f[0] + f[1] == e[0] and any(q[0] == n[0] for q in f[9])]
                if prev:
                    for q in prev[0][9]:
                        if q[0] == n[0] and not (q[4] & 2): q[4] |= 2; fixed += 1
                else:
                    n[4] &= ~1; dropped += 1
    d['ev'] = ev
    json.dump(d, open(path, 'w'), separators=(',', ':'))
    return fixed, dropped
if __name__ == '__main__':
    for p in sys.argv[1:]: print(p, fix(p))
