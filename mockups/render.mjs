import { chromium } from '/tmp/pw/node_modules/playwright-core/index.mjs';
const b = await chromium.launch({ executablePath: '/usr/bin/google-chrome', args:['--no-sandbox'] });
const url = 'file:///workspace/charge-brake/mockups/index.html';
const jobs = [
  ['cover', {width:360,height:850}, 3],
  ['inner', {width:750,height:861}, 8/3],
  ['cover-states', {width:360,height:850}, 3, 'states'],
];
for (const [name, vp, dsf, scr] of jobs) {
  const p = await b.newPage({ viewport: vp, deviceScaleFactor: dsf });
  await p.goto(url + '?screen=' + (scr || name));
  await p.waitForTimeout(300);
  const o = await p.evaluate(() => { const r = []; for (const e of document.querySelectorAll('.app > *, .left > *, .right > *')) { const b = e.getBoundingClientRect(); if (b.bottom > innerHeight + 0.5 || b.right > innerWidth + 0.5 || e.scrollWidth > e.clientWidth + 1) r.push(e.className + ' bottom=' + Math.round(b.bottom) + ' right=' + Math.round(b.right)); } return r; });
  await p.screenshot({ path: `/workspace/charge-brake/mockups/${name}.png` });
  console.log(name, JSON.stringify(o));
  await p.close();
}
await b.close();
