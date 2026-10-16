import omr, pdfplumber, sys
from PIL import Image, ImageDraw
def show(path, allm, idxs, out, scale=3):
    pdf = pdfplumber.open(path)
    ims = []
    for i in idxs:
        m = allm[i]; res = m['res']; pg = pdf.pages[m['page']]
        top = res['staves'][0]['lines'][0] - 30; bot = res['staves'][-1]['lines'][4] + 30
        crop = pg.crop((max(0, m['x0'] - 25), top, min(pg.width, m['x1'] + 5), bot))
        im = crop.to_image(resolution=72 * scale).original.convert('RGB')
        d = ImageDraw.Draw(im)
        ox, oy = max(0, m['x0'] - 25), top
        for e in m['events']:
            col = (220, 0, 0) if e.kind == 'n' else (0, 120, 0)
            yy = (min([n['y'] for n in e.notes]) if e.notes else getattr(e, 'rest_y', 0))
            lab = f"{e.onset}/{omr.dur_name(e.dur)}"
            d.text(((e.x - ox) * scale, (yy - oy) * scale - 26 + (12 if e.staff else 0)), lab, fill=col)
        ims.append(im)
    W = max(i.width for i in ims); H = sum(i.height for i in ims)
    can = Image.new('RGB', (W, H), 'white'); y = 0
    for i in ims:
        can.paste(i, (0, y)); y += i.height
    can.save(out)
