import { chromium } from '/tmp/pw/node_modules/playwright-core/index.mjs';
import fs from 'fs';
const b = await chromium.launch({ executablePath:'/usr/bin/google-chrome', args:['--no-sandbox'] });
const url='file:///workspace/charge-brake/mockups/bubble/index.html?manual=1';
const mode = process.argv[2] || 'png';
if (mode === 'png') {
  const p = await b.newPage({ viewport:{width:360,height:850}, deviceScaleFactor:3 });
  await p.goto(url); await p.waitForTimeout(300);
  for (const t of (process.argv[3]||'5').split(',')) {
    await p.evaluate(t=>window.__seek(t), parseFloat(t));
    await p.screenshot({ path: t===(process.argv[3]||'5').split(',')[0] ? '/workspace/charge-brake/mockups/bubble/bubble-options.png' : `/tmp/bub-${t}.png` });
  }
} else {
  const FPS=30, SECS=20, dir='/tmp/bubframes';
  fs.rmSync(dir,{recursive:true,force:true}); fs.mkdirSync(dir,{recursive:true});
  const p = await b.newPage({ viewport:{width:360,height:850}, deviceScaleFactor:1000/850 });
  await p.goto(url); await p.waitForTimeout(300);
  await p.evaluate(()=>window.__seek(0));
  for (let i=0;i<FPS*SECS;i++){ await p.evaluate(()=>window.__advance(1/30)); await p.screenshot({ path:`${dir}/${String(i).padStart(5,'0')}.png` }); }
}
await b.close();
