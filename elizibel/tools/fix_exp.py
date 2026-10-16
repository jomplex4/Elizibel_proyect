import json
d=json.load(open('songs/experience.json'))
t0=d['measures'][16][3]; L=d['measures'][16][4]
inm=[e for e in d['ev'] if t0<=e[0]<t0+L]; rest=[e for e in d['ev'] if not (t0<=e[0]<t0+L)]
s1=sorted([e for e in inm if e[7]==0 and e[2]==1], key=lambda e:e[0]); tmpl=s1[1]
def mk(t,note):
    e=json.loads(json.dumps(tmpl)); e[0]=t0+t; e[9]=[json.loads(json.dumps(note))]; return e
C6=s1[0][9][0]; C5=s1[1][9][0]; A5=s1[2][9][0]
assert (C6[0],C5[0],A5[0])==(85,73,81), (C6[0],C5[0],A5[0])
new=[mk(g*480+k*120,n) for g in range(4) for k,n in enumerate([C6,A5,C5,A5])]
others=[e for e in inm if not (e[7]==0 and e[2]==1)]
for e in others:
    if e[7]==0 and e[2]==0: e[0]=t0+(0 if e[0]==t0 else 960)
s2=sorted([e for e in others if e[7]==1 and e[2]==0],key=lambda e:e[0])
for i,e in enumerate(s2): e[0]=t0+i*480
d['ev']=sorted(rest+others+new,key=lambda e:(e[0],e[2]))
json.dump(d,open('songs/experience.json','w'),separators=(',',':'))
print('experience m17 corregido')
