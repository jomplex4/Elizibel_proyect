"""Listen to Your Heart: en el estribillo la melodia hace B-B, A#-A#, G#-G#, A#-A#, A-A, B-B; el A natural rompe
la linea (debe ser la sensible A#, como en la primera mitad). Se corrige en todas las repeticiones."""
import json
d = json.load(open('songs/listen.json'))
rh = sorted([e for e in d['ev'] if e[2] == 0 and not e[8] and len(e[9]) == 1], key=lambda e: e[0])
seq = [e[9][0][0] for e in rh]
pat = [70, 70, 69, 69, 71, 71]
n = 0
for i in range(len(seq) - 5):
    if seq[i:i + 6] == pat:
        for e in rh[i + 2:i + 4]:
            e[9][0][0] = 70; e[9][0][2] = 1; n += 1
json.dump(d, open('songs/listen.json', 'w'), separators=(',', ':'))
print('notas corregidas', n)
