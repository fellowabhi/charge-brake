import { chromium } from '/tmp/pw/node_modules/playwright-core/index.mjs';
const b = await chromium.launch({ executablePath:'/usr/bin/google-chrome', args:['--no-sandbox'] });
const url='file:///workspace/charge-brake/mockups/index.html';
const jobs=[['cover',{width:360,height:850},3,3.5],['inner',{width:750,height:861},8/3,9],['notification',{width:360,height:460},3,0,'notif']];
for(const [name,vp,dsf,t,scr] of jobs){
  const p=await b.newPage({viewport:vp,deviceScaleFactor:dsf});
  await p.goto(`${url}?screen=${scr||name}&manual=1`);
  await p.evaluate(t=>window.__seek&&window.__seek(t), t);
  const o=await p.evaluate(()=>{const r=[];for(const e of document.querySelectorAll('.app > *, .panel > *, .bottom > *, .gcell > *, .shade > *')){const b=e.getBoundingClientRect(); if(b.bottom>innerHeight+0.5||b.right>innerWidth+0.5) r.push(e.className+' b='+Math.round(b.bottom)+' r='+Math.round(b.right));}return r;});
  await p.screenshot({path:`/workspace/charge-brake/mockups/${name}.png`});
  console.log(name, JSON.stringify(o)); await p.close();
}
await b.close();
