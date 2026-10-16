import subprocess, numpy as np, json, os, glob, re
SR = 32000
def load(p):
    raw = subprocess.run(['ffmpeg','-v','error','-i',p,'-ac','1','-ar',str(SR),'-f','f32le','-'], capture_output=True).stdout
    return np.frombuffer(raw, np.float32).copy()
def save(x, p):
    pcm = (np.clip(x, -1, 1) * 32767).astype(np.int16).tobytes()
    subprocess.run(['ffmpeg','-v','error','-y','-f','s16le','-ar',str(SR),'-ac','1','-i','-','-c:a','libvorbis','-q:a','3',p], input=pcm)
def f0(x):
    seg = x[int(SR*0.4):int(SR*0.4)+4096]
    seg = seg - seg.mean()
    ac = np.correlate(seg, seg, 'full')[len(seg)-1:]
    lo, hi = int(SR/2600), int(SR/200)
    k = lo + np.argmax(ac[lo:hi])
    # refinar con primer pico alto (evita octava baja)
    peaks = [i for i in range(lo, hi) if ac[i] > ac[i-1] and ac[i] >= ac[i+1] and ac[i] > 0.85 * ac[k]]
    k = peaks[0] if peaks else k
    a, b, c = ac[k-1], ac[k], ac[k+1]; k = k + 0.5*(a-c)/(a-2*b+c)
    return SR / k
idx = {'rate': SR, 'inst': {'piano': [], 'flute': []}}
names = {'C':0,'Cs':1,'Ds':3,'D':2,'E':4,'F':5,'Fs':6,'G':7,'A':9,'As':10,'B':11}
for p in sorted(glob.glob('pno/*.mp3')):
    n = os.path.basename(p)[:-4]; m = re.match(r'([A-G]s?)(\d)', n)
    root = 12*(int(m.group(2))+1) + names[m.group(1)]
    x = load(p)
    st = np.argmax(np.abs(x) > 0.003); x = x[max(0, st-40):]
    L = 7.0 if root < 48 else (5.5 if root < 72 else (4.0 if root < 90 else 3.0))
    x = x[:int(SR*L)]
    fade = int(SR*0.6); x[-fade:] *= np.linspace(1, 0, fade)**2
    fn = 'p%d.ogg' % root; save(x, 'out/'+fn)
    idx['inst']['piano'].append(dict(file=fn, root=root, ls=-1, le=-1, peak=float(np.abs(x).max())))
for p in sorted(glob.glob('/tmp/vcsl/Aerophones/Edge-blown Aerophones/Baroque Soprano Recorder/Sustain/*.wav')):
    x = load(p)
    st = np.argmax(np.abs(x) > 0.01); x = x[max(0, st-60):]
    f = f0(x); midi = 69 + 12*np.log2(f/440); root = int(round(midi)); cents = (midi-root)*100
    x = x[:int(SR*2.6)]
    # bucle en la parte estable: longitud multiplo del periodo, con fundido cruzado ya aplicado
    per = SR / f
    ls = int(SR*0.7); n_per = int((SR*1.5) / per); le = ls + int(round(n_per * per))
    xf = 1500
    a = x[le-xf:le].copy(); b = x[ls-xf:ls].copy(); w = np.linspace(0,1,xf)
    x[le-xf:le] = a*(1-w) + b*w
    x = x[:le+1]
    x = x / np.abs(x).max() * 0.8
    fn = 'f%d.ogg' % root; save(x, 'out/'+fn)
    idx['inst']['flute'].append(dict(file=fn, root=root, ls=ls, le=le, cents=round(float(cents),1), src=os.path.basename(p)))
    print(os.path.basename(p), round(f,1), root, round(cents,1))
pk = max(s['peak'] for s in idx['inst']['piano'])
for s in idx['inst']['piano']: s['gain'] = round(0.85/pk, 3)
json.dump(idx, open('out/index.json','w'), indent=0)
