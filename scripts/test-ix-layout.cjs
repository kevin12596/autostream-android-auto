const { chromium } = require('playwright');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const layout = fs.readFileSync('app/src/main/assets/ix-layout.js','utf8');
const scrollCode = fs.readFileSync('app/src/main/java/com/kcliang/autostream/NestedScrollJs.kt','utf8').match(/private val SCRIPT = """([\s\S]*?)"""\.trimIndent\(\)/)[1];
const poster='data:image/svg+xml,'+encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="900"><defs><linearGradient id="g"><stop stop-color="#163450"/><stop offset="1" stop-color="#5a274e"/></linearGradient></defs><rect width="1600" height="900" fill="url(#g)"/><text x="800" y="450" fill="white" font-size="64" text-anchor="middle">VIDEO 16:9</text></svg>');
function cards(prefix,count,current=false){return Array.from({length:count},(_,i)=>`<ytm-compact-video-renderer><a href="/watch?v=${current&&i===0?'abcdefghijk':prefix+String(i).padStart(6,'0')}${prefix==='queue'?'&list=PLtest&index='+(i+1):''}"><h3>爵士音樂 ${i+1}・寬螢幕推薦標題</h3><div class="media-item-byline">測試頻道</div></a></ytm-compact-video-renderer>`).join('');}
function html(path){return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><style>body{margin:0}ytm-app{display:block;transform:translateZ(0);position:relative;z-index:4}#player-container-id{width:600px;height:340px}video{width:100%;height:100%}</style></head><body><ytm-app><ytm-watch>${path==='/watch'?`<div id="player-container-id" style="transform:translateY(45%);padding-top:35px;min-height:800px;min-width:1600px"><ytm-player style="display:block;height:90px"><div id="player"><div id="unknown-wrapper" style="position:relative;transform:translateY(120px);padding-top:56.25%;will-change:transform;min-height:900px"><div id="movie_player"><div class="html5-video-container"><video style="margin-top:110px;transform:translateY(50%);min-height:1000px" poster="${poster}"></video></div></div></div></div></ytm-player></div>`:''}<div id="related"><ytm-watch-next-secondary-results-renderer>${cards('video',24)}</ytm-watch-next-secondary-results-renderer></div><ytm-playlist-panel-renderer>${cards('queue',12,true)}</ytm-playlist-panel-renderer></ytm-watch></ytm-app></body></html>`;}
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||undefined,headless:true,args:['--no-sandbox']});
 try{
 const page=await browser.newPage({viewport:{width:1280,height:480}});
 await page.route('**/*',route=>{const u=new URL(route.request().url());
  if(route.request().resourceType()==='image')return route.fulfill({contentType:'image/svg+xml',body:'<svg xmlns="http://www.w3.org/2000/svg" width="176" height="100"><rect width="176" height="100" fill="#284961"/><text x="88" y="58" text-anchor="middle" font-size="22" fill="white">NEXT</text></svg>'});
  return route.fulfill({contentType:'text/html; charset=utf-8',body:html(u.pathname)});});
 await page.addInitScript(layout);
 await page.goto('https://m.youtube.com/watch?v=abcdefghijk');
 await page.waitForSelector('#as-ix-list .as-ix-card');
 await page.evaluate(()=>window.savedVideo=document.querySelector('video'));
 for(const size of [{width:1280,height:480},{width:960,height:360},{width:720,height:320},{width:1600,height:600}]){
  await page.setViewportSize(size); await page.waitForTimeout(600);
  const boxes=await page.evaluate(()=>{const p=document.querySelector('[data-as-ix-player]').getBoundingClientRect(),v=document.querySelector('video').getBoundingClientRect(),s=document.querySelector('#as-ix-side').getBoundingClientRect(),l=document.querySelector('#as-ix-list');return {video:{x:v.x,y:v.y,w:v.width,h:v.height},player:{x:p.x,y:p.y,w:p.width,h:p.height},side:{x:s.x,w:s.width,h:s.height},count:l.children.length,target:document.elementFromPoint(100,100).tagName,videoSame:window.savedVideo===document.querySelector('video'),rowHeight:l.firstChild.getBoundingClientRect().height};});
  assert.ok(Math.abs(boxes.player.w+boxes.side.w-size.width)<2);
  assert.ok(Math.abs(boxes.player.h-size.height)<2);
  assert.ok(Math.abs(boxes.side.x-boxes.player.w)<2);
  assert.ok(Math.abs(boxes.video.y-boxes.player.y)<1);assert.ok(Math.abs(boxes.video.h-boxes.player.h)<1);assert.ok(Math.abs(boxes.video.w-boxes.player.w)<1);
  assert.equal(boxes.count,24);assert.equal(boxes.videoSame,true);assert.equal(boxes.target,'VIDEO');assert.ok(boxes.rowHeight>=76);
 }
 await page.setViewportSize({width:1280,height:480});await page.waitForTimeout(600);
 const noMove=await page.evaluate(code=>{const l=document.querySelector('#as-ix-list');(0,eval)('('+code+')')(0,220,0.2,0.5);return l.scrollTop;},scrollCode);
 assert.equal(noMove,0);
 await page.locator('#as-ix-scroll-mode').click();
 const nested=await page.evaluate(code=>{const l=document.querySelector('#as-ix-list');(0,eval)('('+code+')')(0,220,null,null);return {scroll:l.scrollTop,page:document.scrollingElement.scrollTop};},scrollCode);
 assert.ok(nested.scroll>0);assert.equal(nested.page,0);
 if(process.env.IX_SCREENSHOT){await page.locator('#as-ix-list').evaluate(n=>n.scrollTop=0);await page.waitForTimeout(200);await page.screenshot({path:process.env.IX_SCREENSHOT});}
 // Real moving 4:3 media: fullscreen must keep the existing stream and its ratio.
 await page.evaluate(async()=>{
  const canvas=document.createElement('canvas');canvas.width=640;canvas.height=480;
  const ctx=canvas.getContext('2d');ctx.fillStyle='#184969';ctx.fillRect(0,0,640,480);
  ctx.fillStyle='white';ctx.font='48px sans-serif';ctx.fillText('VIDEO 4:3',180,240);
  const video=document.querySelector('video');video.muted=true;video.srcObject=canvas.captureStream(10);
  await video.play();window.testCanvas=canvas;
 });
 async function checkFullscreen(){
  await page.waitForSelector('html.as-ix-fullscreen');
  assert.equal(await page.locator('#as-ix-shell').count(),0);
  const box=await page.evaluate(()=>{const v=document.querySelector('video'),r=v.getBoundingClientRect();return {x:r.x,y:r.y,w:r.width,h:r.height,fit:getComputedStyle(v).objectFit,ratio:v.videoWidth/v.videoHeight,playing:!v.paused,same:window.savedVideo===v};});
  assert.ok(Math.abs(box.x)<1 && Math.abs(box.y)<1,JSON.stringify(box));
  assert.ok(Math.abs(box.w-1280)<1 && Math.abs(box.h-480)<1,JSON.stringify(box));
  assert.equal(box.fit,'contain');assert.equal(box.ratio,4/3);assert.equal(box.playing,true);assert.equal(box.same,true);
 }
 // Android WebView's custom-view callback can precede/miss the DOM fullscreen event.
 await page.evaluate(()=>window.__asIxLayout.setFullscreen(true));
 await checkFullscreen();
 await page.evaluate(()=>window.__asIxLayout.setFullscreen(false));
 await page.waitForSelector('#as-ix-list .as-ix-card');
 for(const selector of ['#movie_player','video']){
  await page.locator('video').click();
  await page.evaluate(selector=>document.querySelector(selector).requestFullscreen(),selector);
  await checkFullscreen();
  await page.evaluate(()=>document.exitFullscreen());
  await page.waitForSelector('#as-ix-list .as-ix-card');
 }
 // Original-page preference must survive fullscreen entry/exit too.
 await page.evaluate(()=>{window.__asIxLayout.setEnabled(false);window.__asIxLayout.setFullscreen(true);});
 await checkFullscreen();
 await page.evaluate(()=>window.__asIxLayout.setFullscreen(false));
 assert.equal(await page.locator('html.as-ix').count(),0);
 await page.evaluate(()=>window.__asIxLayout.setEnabled(true));
 await page.waitForSelector('#as-ix-list .as-ix-card');
 await page.getByRole('tab',{name:'播放清單',exact:true}).click();
 assert.equal(await page.locator('#as-ix-list .as-ix-card').count(),12);
 assert.equal(await page.locator('#as-ix-list [aria-current=true]').count(),1);
 await page.getByRole('button',{name:'下一頁 ↓',exact:true}).click();
 assert.ok(await page.locator('#as-ix-list').evaluate(n=>n.scrollTop)>0);
 await page.getByRole('tab',{name:'推薦',exact:true}).click();
 await page.locator('#as-ix-list .as-ix-card').first().click();
 await page.waitForURL(/v=video000000/);
 await page.waitForSelector('#as-ix-shell');
 await page.evaluate(()=>window.__asIxLayout.setEnabled(false));
 await page.waitForSelector('#as-ix-shell',{state:'detached'});
 const originalScroll=await page.evaluate(code=>{(0,eval)('('+code+')')(0,180,null,null);return document.scrollingElement.scrollTop;},scrollCode);
 assert.ok(originalScroll>0);
 await page.waitForTimeout(2100);assert.equal(await page.locator('#as-ix-shell').count(),0);
 await page.evaluate(()=>window.__asIxLayout.setEnabled(true));
 await page.waitForSelector('#as-ix-shell');
 await page.goto('https://m.youtube.com/results?search_query=music');
 assert.equal(await page.locator('#as-ix-shell').count(),0);
 console.log('PASS: real Chromium wide layout at 4 viewport sizes, offset/transform recovery and player visibility/identity, row sizes, independent scrolling, tabs, pagination, selection, wrapper/video/native fullscreen geometry with live 4:3 playback, mode restoration and non-watch cleanup');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
