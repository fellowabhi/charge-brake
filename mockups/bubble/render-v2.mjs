import { chromium } from '/tmp/pw/node_modules/playwright-core/index.mjs';
import fs from 'fs';
const b = await chromium.launch({ executablePath:'/usr/bin/google-chrome', args:['--no-sandbox'] });
const url='file:///workspace/charge-brake/mockups/bubble/index-v2.html?manual=1';
const mode=process.argv[2]||'png';
if (mode==='png'){
  const p=await b.newPage({viewport:{width:360,height:850},deviceScaleFactor:3}); await p.goto(url); await p.waitForTimeout(300);
  const ts=(process.argv[3]||'10.5').split(',');
  for (const [i,t] of ts.entries()){ await p.evaluate(t=>window.__seek(t),parseFloat(t));
    await p.screenshot({path: i===0 ? '/workspace/charge-brake/mockups/bubble/bubble-v2.png' : `/tmp/bv2-${t}.png`}); }
} else {
  const dir='/tmp/bv2frames'; fs.rmSync(dir,{recursive:true,force:true}); fs.mkdirSync(dir,{recursive:true});
  const p=await b.newPage({viewport:{width:360,height:850},deviceScaleFactor:1000/850}); await p.goto(url); await p.waitForTimeout(300);
  await p.evaluate(()=>window.__seek(0));
  for(let i=0;i<600;i++){ await p.evaluate(()=>window.__advance(1/30)); await p.screenshot({path:`${dir}/${String(i).padStart(5,'0')}.png`}); }
}
await b.close();
