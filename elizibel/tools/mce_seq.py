import mce_det, numpy as np, json
def rows():
    res=[]
    for p in (1,2):
        out,im=mce_det.analyze('mce/p-%d.png'%p)
        for i,s in enumerate(out):
            st=s['st']; sp=s['sp']
            hs=[h for h in s['heads'] if h[0]>440 and -2<=h[3]<=15]
            # accidental: hueca/rellena pequena justo a la izquierda (<=1.7 sp) de otra cabeza a +-2 posiciones
            keep=[]; acc={}
            for k,h in enumerate(hs):
                nxt=[g for g in hs if 0<g[0]-h[0]<=1.9*sp and abs(g[3]-h[3])<=2 and g is not h]
                if h[2]=='o' and nxt: acc[id(nxt[0])]=True; continue
                keep.append(h)
            # barras de compas: lineas verticales completas sin cabeza cerca
            bars=[b for b in s['bars'] if b>440 and all(abs(b-h[0])>0.8*sp for h in keep)]
            mb=[]
            for b in bars:
                if not mb or b-mb[-1]>1.6*sp: mb.append(b)
            res.append(dict(p=p,i=i+1,sp=sp,heads=[(round(h[0]),h[1],h[2],bool(acc.get(id(h)))) for h in keep],bars=mb))
    return res
if __name__=='__main__':
    for r in rows():
        print('P%d F%d'%(r['p'],r['i']),'barras',r['bars'])
        edges=[440]+r['bars']
        if edges[-1]<2100: edges.append(2300)
        for m in range(len(edges)-1):
            hs=[h for h in r['heads'] if edges[m]<h[0]<edges[m+1]]
            print('   ',' '.join(h[1]+('o' if h[2]=='o' else '')+('#' if h[3] else '') for h in hs))
