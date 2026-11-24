// Compose les captures des stores : capture brute dans un cadre de téléphone, légende au-dessus.
// Usage : node compose.js <dossier-brut> <locale> <dossier-sortie>
// Entrée : <dossier-brut>/<locale>/NN-*.png (branche store-media-raw). Sortie : 1.png, 2.png… (1080 × 1920).
// Logo : SVG de docs/brand (logo horizontal sur fond bleu nuit, fond retiré pour la superposition).
const fs = require('fs'), path = require('path');
const { chromium } = require('playwright');
const [raw, locale, out] = process.argv.slice(2);
const texts = JSON.parse(fs.readFileSync(path.join(__dirname, 'captions.json'), 'utf8'))[locale];
const brand = path.join(__dirname, '..', '..', 'docs', 'brand');
const lang = locale.slice(0, 2);
const logo = fs.readFileSync(path.join(brand, `nextglyph-horizontal-${lang}-dark.svg`), 'utf8').replace(/<rect width="989" height="300" fill="#0B1B3A"\/>/, '');
const dir = path.join(raw, locale);
const shots = fs.readdirSync(dir).filter(f => /^0[1-6]-.*\.png$/.test(f)).sort();
const page = (img, caption, first) => `<!doctype html><meta charset=utf-8><style>
html,body{margin:0;width:1080px;height:1920px;overflow:hidden;font-family:Inter,sans-serif}
body{background:radial-gradient(900px 700px at 85% 8%,#4F7CF733,transparent),radial-gradient(800px 800px at 0% 100%,#7C3AED33,transparent),#0B1B3A;display:flex;flex-direction:column;align-items:center}
.head{height:420px;display:flex;flex-direction:column;justify-content:center;align-items:center;text-align:center;padding:0 70px;box-sizing:border-box;width:100%}
.brand{margin-bottom:22px} .brand svg{height:110px;width:auto;display:block}
.cap{color:#fff;font-size:78px;font-weight:700;line-height:1.08;letter-spacing:-1.5px}
.cap b{background:linear-gradient(90deg,#7C3AED,#4F7CF7,#22D3EE);-webkit-background-clip:text;color:transparent}
.phone{width:640px;border-radius:56px;background:#000;border:2px solid #2A3B5E;padding:18px;box-shadow:0 30px 80px #0008}
.phone img{width:100%;display:block;border-radius:42px}
</style><div class=head><div class=brand>${logo}</div><div class=cap>${caption}</div></div>
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
