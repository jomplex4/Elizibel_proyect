"""Dulce Navidad: el silencio del "¡Hey!" (compases 8, 40 y 72) pasa a ser la nota aguda de la hoja de flauta del usuario."""
import json
def patch(path, midi, dia):
    d = json.load(open(path)); ms = d['measures']; n = 0
    for mi in (7, 39, 71):
        t0 = ms[mi][3]
        for e in d['ev']:
            if e[8] and e[0] == t0 + 480 and e[1] == 480:
                e[8] = 0; e[9] = [[midi, dia, 0, 9, 0, 0]]; e[:] = e[:10] + [480]; n += 1
    json.dump(d, open(path, 'w'), separators=(',', ':'))
    return n
if __name__ == '__main__':
    print('piano', patch('songs/p_dulce.json', 72, 35))   # Do5 (en Fa)
    print('flauta', patch('songs/f_dulce.json', 74, 36))  # Re5 (en Sol)
