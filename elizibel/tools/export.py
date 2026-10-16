import omr, json, sys
Q = omr.Q

def repeats(m):
    """Marca inicio/fin de repeticion segun los puntos de repeticion junto a cada barra."""
    res = m['res']; sp = res['sp']
    st, en = False, False
    for g in res['G']:
        if g.code != 0xE044:
            continue
        if abs(g.x - m['x0']) < sp * 2.2 and g.x > m['x0']:
            st = True
        if abs(g.x - m['x1']) < sp * 2.2 and g.x < m['x1']:
            en = True
    return st, en

def expand(flags):
    order = []; i = 0; start = 0; done = set()
    while i < len(flags):
        st, en = flags[i]
        if st: start = i
        order.append(i)
        if en and i not in done:
            done.add(i); i = start; continue
        i += 1
    return order

def build(meta, parts, out):
    """parts: lista de (allm, staves_a_usar, part_index). El primer elemento define los compases."""
    base = parts[0][0]
    measures = []; tick = 0; flags = []
    for m in base:
        ts = m['time']; key = list(m['key'].values())[0] if m['key'] else 0
        measures.append([ts[0], ts[1], key, tick, m['mlen']])
        tick += m['mlen']
        flags.append(repeats(m))
    ev = []
    for (allm, staves, pidx) in parts:
        assert len(allm) == len(base), (len(allm), len(base))
        for mi, m in enumerate(allm):
            t0 = measures[mi][3]
            for e in m['events']:
                if e.staff not in staves:
                    continue
                stf = staves.index(e.staff)
                if e.kind == 'r':
                    if e.measure_rest: continue
                    ev.append([t0 + e.onset, e.dur, stf, 0, 0, e.dots, 0, pidx, 1, []])
                    continue
                notes = []
                for n in e.notes:
                    tie = (1 if n.get('tie_in') else 0) | (2 if n.get('tie_out') else 0)
                    notes.append([n['midi'], n['d'], n['alt'], 9 if n['shown'] is None else n['shown'], tie, n['oct']])
                ev.append([t0 + e.onset, e.dur, stf, 1 if e.stem == 'u' else (2 if e.stem == 'd' else 0), (hash(e.beam_id) % 99991) if e.beam_id else 0,
                           e.dots, e.tuplet[0] if e.tuplet else 0, pidx, 0, notes, e.base])
    # ligaduras entre sistemas: la nota siguiente de igual altura recibe tie_in
    ev.sort(key=lambda x: (x[0], x[2]))
    d = dict(meta); d['measures'] = measures; d['order'] = meta.get('order') or expand(flags); d['ev'] = ev
    json.dump(d, open(out, 'w'), separators=(',', ':'))
    return d


def tempos(allm):
    out = []; seen = set()
    for mi, m in enumerate(allm):
        res = m['res']
        if id(res) in seen: continue
        seen.add(id(res))
        tx = sorted(res['txt'], key=lambda t: (round(t['y']), t['x']))
        for i, t in enumerate(tx):
            if t['t'] == '=':
                ds = ''
                for u in tx[i + 1:i + 5]:
                    if abs(u['y'] - t['y']) < 3 and (u['t'].isdigit() or u['t'] in ' \t'):
                        ds += u['t']
                    else:
                        break
                ds = ds.strip()
                if ds.isdigit():
                    # compas de ese sistema que contiene x
                    for k, mm in enumerate(allm):
                        if mm['res'] is res and mm['x0'] - 20 <= t['x'] <= mm['x1']:
                            out.append([k, int(ds)]); break
    out.sort()
    return out
