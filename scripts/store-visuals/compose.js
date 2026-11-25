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
// Captures 01 à 04 : on ne garde que le bas de l'écran (conversation et clavier), agrandi ; 05 et 06 : écran entier
// sans barres système (la barre d'état, en clair sur fond clair, serait illisible).
const CROP = { top: 840, bottom: 2290, width: 1080 };
const page = (img, caption, first, crop) => `<!doctype html><meta charset=utf-8><style>
html,body{margin:0;width:1080px;height:1920px;overflow:hidden;font-family:Inter,sans-serif}
body{background:radial-gradient(900px 700px at 85% 8%,#4F7CF733,transparent),radial-gradient(800px 800px at 0% 100%,#7C3AED33,transparent),#0B1B3A;display:flex;flex-direction:column;align-items:center}
.head{height:460px;display:flex;flex-direction:column;justify-content:center;align-items:center;text-align:center;padding:0 70px;box-sizing:border-box;width:100%}
.brand{margin-bottom:22px} .brand svg{height:130px;width:auto;display:block}
.cap{color:#fff;font-size:78px;font-weight:700;line-height:1.08;letter-spacing:-1.5px}
.cap b{background:linear-gradient(90deg,#7C3AED,#4F7CF7,#22D3EE);-webkit-background-clip:text;color:transparent}
.phone{width:640px;border-radius:56px;background:#000;border:2px solid #2A3B5E;padding:18px;box-shadow:0 30px 80px #0008}
.phone img{width:100%;display:block;border-radius:42px}
.crop{width:1000px;border-radius:44px;overflow:hidden;box-shadow:0 30px 80px #0008;border:2px solid #2A3B5E;margin-top:30px}
.crop div{width:1000px;height:${(CROP.bottom-CROP.top)*1000/CROP.width}px;overflow:hidden;position:relative}
.crop img{position:absolute;left:0;top:-${CROP.top*1000/CROP.width}px;width:1000px}
.screen{width:680px;border-radius:44px;overflow:hidden;box-shadow:0 30px 80px #0008;border:2px solid #2A3B5E;margin-top:30px}
.screen div{width:680px;height:${(2290-100)*680/1080}px;overflow:hidden;position:relative}
.screen img{position:absolute;left:0;top:-${100*680/1080}px;width:680px}
</style><div class=head><div class=brand>${logo}</div><div class=cap>${caption}</div></div>
<div class=${crop ? 'crop' : 'screen'}><div><img src="data:image/png;base64,${fs.readFileSync(img).toString("base64")}"></div></div>`;
(async () => {
  fs.mkdirSync(out, { recursive: true });
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1080, height: 1920 } });
  let n = 0;
  for (const f of shots) {
    const caption = texts.shots[f.slice(0, 2)]; if (!caption) continue;
    n++;
    await p.setContent(page(path.resolve(dir, f), caption, n === 1, Number(f.slice(0, 2)) <= 4), { waitUntil: 'load' });
    await p.screenshot({ path: path.join(out, `${n}.png`) });
    console.log(`${f} -> ${n}.png`);
  }
  await b.close();
})();
