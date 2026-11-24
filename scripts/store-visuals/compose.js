// Compose les captures des stores : capture brute dans un cadre de téléphone, légende au-dessus.
// Usage : node compose.js <dossier-brut> <locale> <dossier-sortie>
// Entrée : <dossier-brut>/<locale>/NN-*.png (branche store-media-raw). Sortie : 1.png, 2.png… (1080 × 1920).
// Le logo (en attente) se remplace en déposant logo.svg à côté de ce script.
const fs = require('fs'), path = require('path');
const { chromium } = require('playwright');
const [raw, locale, out] = process.argv.slice(2);
const texts = JSON.parse(fs.readFileSync(path.join(__dirname, 'captions.json'), 'utf8'))[locale];
const logoPath = path.join(__dirname, 'logo.svg');
const logo = fs.existsSync(logoPath) ? fs.readFileSync(logoPath, 'utf8') : '';
const dir = path.join(raw, locale);
const shots = fs.readdirSync(dir).filter(f => /^0[1-6]-.*\.png$/.test(f)).sort();
const page = (img, caption, first) => `<!doctype html><meta charset=utf-8><style>
html,body{margin:0;width:1080px;height:1920px;overflow:hidden;font-family:Inter,sans-serif}
body{background:linear-gradient(160deg,#2F5D8A 0%,#1E4470 60%,#16324F 100%);display:flex;flex-direction:column;align-items:center}
.head{height:420px;display:flex;flex-direction:column;justify-content:center;align-items:center;text-align:center;padding:0 70px;box-sizing:border-box;width:100%}
.brand{display:flex;align-items:center;gap:18px;color:#DCE6F2;font-size:38px;font-weight:600;margin-bottom:26px}
.brand svg{width:64px;height:64px;border-radius:30%}
.cap{color:#fff;font-size:78px;font-weight:700;line-height:1.08;letter-spacing:-1.5px}
.cap b{color:#F5B942;font-weight:700}
.phone{width:640px;border-radius:56px;background:#0E1621;padding:18px;box-shadow:0 30px 80px #0008}
.phone img{width:100%;display:block;border-radius:42px}
</style><div class=head><div class=brand>${logo}<span>${first ? texts.tagline : 'NextGlyph'}</span></div><div class=cap>${caption}</div></div>
<div class=phone><img src="data:image/png;base64,${fs.readFileSync(img).toString("base64")}"></div>`;
(async () => {
  fs.mkdirSync(out, { recursive: true });
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1080, height: 1920 } });
  let n = 0;
  for (const f of shots) {
    const caption = texts.shots[f.slice(0, 2)]; if (!caption) continue;
    n++;
    await p.setContent(page(path.resolve(dir, f), caption, n === 1), { waitUntil: 'load' });
    await p.screenshot({ path: path.join(out, `${n}.png`) });
    console.log(`${f} -> ${n}.png`);
  }
  await b.close();
})();
