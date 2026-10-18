import { chromium } from '/tmp/pw/node_modules/playwright-core/index.mjs';
import fs from 'fs';
const b = await chromium.launch({ executablePath:'/usr/bin/google-chrome', args:['--no-sandbox'] });
const url='file:///workspace/charge-brake/mockups/index.html';
const FPS=30, SECS=18;
for (const [name,vp,dsf] of [['cover',{width:360,height:850},1.2],['inner',{width:750,height:861},1000/861]]) {
  const dir=`/tmp/frames/${name}`; fs.rmSync(dir,{recursive:true,force:true}); fs.mkdirSync(dir,{recursive:true});
  const p=await b.newPage({viewport:vp,deviceScaleFactor:dsf});
  await p.goto(`${url}?screen=${name}&manual=1`);
  for(let i=0;i<FPS*SECS;i++){
    await p.evaluate(()=>window.__advance(1/30));
    await p.screenshot({path:`${dir}/${String(i).padStart(5,'0')}.jpg`, type:'jpeg', quality:92});
  }
  console.log(name,'done'); await p.close();
}
await b.close();
