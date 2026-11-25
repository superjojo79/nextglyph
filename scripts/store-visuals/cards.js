// Rend les cartons de la vidéo promo (1080 × 1920, PNG) : intro, légendes transparentes, fin.
// Usage : node cards.js <locale> <dossier-sortie>
const fs = require('fs'), path = require('path');
const { chromium } = require('playwright');
const [locale, out] = process.argv.slice(2);
const v = JSON.parse(fs.readFileSync(path.join(__dirname, 'captions.json'), 'utf8'))[locale].video;
const brand = path.join(__dirname, '..', '..', 'docs', 'brand');
const lang = locale.slice(0, 2);
const logo = fs.readFileSync(path.join(brand, `nextglyph-horizontal-${lang}-dark.svg`), 'utf8').replace(/<rect width="989" height="300" fill="#0B1B3A"\/>/, '');
const mark = fs.readFileSync(path.join(brand, 'nextglyph-mark.svg'), 'utf8');
const bg = 'radial-gradient(900px 700px at 85% 8%,#4F7CF733,transparent),radial-gradient(800px 800px at 0% 100%,#7C3AED33,transparent),#0B1B3A';
const base = `<!doctype html><meta charset=utf-8><style>html,body{margin:0;width:1080px;height:1920px;overflow:hidden;font-family:Inter,sans-serif}
.full{width:1080px;height:1920px;background:${bg};display:flex;flex-direction:column;align-items:center;justify-content:center;color:#fff;text-align:center}
.full svg{width:860px;height:auto} .mark svg{width:420px;height:auto;margin-bottom:60px}
.line{font-size:64px;font-weight:700;margin:14px 0;letter-spacing:-1px} .sub{font-size:44px;color:#B8C4D8;margin-top:40px}
.cap{position:absolute;top:0;left:0;width:1080px;height:460px;display:flex;align-items:center;justify-content:center;padding:0 70px;box-sizing:border-box;
color:#fff;font-size:78px;font-weight:700;line-height:1.08;letter-spacing:-1.5px;text-align:center}</style>`;
(async () => {
  fs.mkdirSync(out, { recursive: true });
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1080, height: 1920 } });
  const shot = async (html, file, transparent) => {
    await p.setContent(base + html, { waitUntil: 'load' });
    await p.screenshot({ path: path.join(out, file), omitBackground: !!transparent });
  };
  await shot(`<div class=full><div>${logo}</div></div>`, 'intro.png');
  await shot(`<div class=full style="background:${bg}"></div>`, 'fond.png');
  for (const [i, s] of v.steps.entries()) await shot(`<div class=cap>${s}</div>`, `legende-${i + 1}.png`, true);
  await shot(`<div class=full><div class=mark>${mark}</div>${v.outro.map(l => `<div class=line>${l}</div>`).join('')}<div class=sub>NextGlyph</div></div>`, 'fin.png');
  await b.close();
})();
