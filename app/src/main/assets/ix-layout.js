(function () {
  'use strict';
  if (window.top !== window || !/^(m\.|www\.)?youtube\.com$/.test(location.hostname)) return;
  if (window.__asIxLayout) { window.__asIxLayout.refresh(); return; }
  var tab = 'recommend', player = null, shell = null, list = null, style = null;
  var timer = null, signature = '', lastWatch = '', scrollButton = null;
  window.__asIxScrollPane = 'player';
  var css = `
    html.as-ix,html.as-ix body { overflow:hidden!important; transform:none!important; filter:none!important; contain:none!important; will-change:auto!important; margin:0!important; padding:0!important; }
    html.as-ix [data-as-ix-ancestor] { transform:none!important; filter:none!important; perspective:none!important; contain:none!important; overflow:visible!important; z-index:auto!important; isolation:auto!important; opacity:1!important; position:static!important; will-change:auto!important; clip-path:none!important; }
    html.as-ix [data-as-ix-player] { position:fixed!important; top:0!important; left:0!important; width:calc(100vw - var(--as-ix-side))!important; height:100vh!important; min-height:0!important; min-width:0!important; max-height:none!important; max-width:none!important; margin:0!important; padding:0!important; transform:none!important; filter:none!important; will-change:auto!important; aspect-ratio:auto!important; box-sizing:border-box!important; clip:auto!important; clip-path:none!important; overflow:hidden!important; background:#000!important; z-index:2147483601!important; }
    html.as-ix [data-as-ix-inner],html.as-ix [data-as-ix-player] ytm-player,html.as-ix [data-as-ix-player] #player,html.as-ix [data-as-ix-player] > #container,html.as-ix [data-as-ix-player] #movie_player,html.as-ix [data-as-ix-player] .html5-video-container { width:100%!important; height:100%!important; min-height:0!important; min-width:0!important; max-height:none!important; max-width:none!important; position:absolute!important; inset:0!important; margin:0!important; padding:0!important; transform:none!important; will-change:auto!important; aspect-ratio:auto!important; box-sizing:border-box!important; clip:auto!important; clip-path:none!important; }
    html.as-ix video[data-as-ix-player],html.as-ix [data-as-ix-player] video { position:absolute!important; object-fit:contain!important; object-position:center!important; width:100%!important; height:100%!important; max-width:none!important; min-height:0!important; min-width:0!important; max-height:none!important; inset:0!important; margin:0!important; padding:0!important; transform:none!important; }
    html.as-ix.as-ix-fullscreen [data-as-ix-player] { width:100vw!important; height:100vh!important; }
    #as-ix-shell { position:fixed; inset:0; background:#000; z-index:2147483600; pointer-events:none; font:18px/1.3 sans-serif; color:#f5f5f5; }
    #as-ix-side { position:absolute; right:0; top:0; bottom:0; width:var(--as-ix-side); background:#15171b; border-left:1px solid #363b43; box-sizing:border-box; display:flex; flex-direction:column; pointer-events:auto; }
    #as-ix-shell * { box-sizing:border-box; }
    #as-ix-shell button { font:inherit; color:inherit; border:0; border-radius:0; background:transparent; cursor:pointer; }
    #as-ix-tabs,#as-ix-pages { display:flex; flex:none; min-height:76px; border-bottom:1px solid #363b43; }
    #as-ix-tabs button,#as-ix-pages button { flex:1; min-width:76px; min-height:76px; }
    #as-ix-tabs button[aria-selected=true] { color:#8dbbff; box-shadow:inset 0 -3px #8dbbff; }
    #as-ix-list { flex:1; min-height:0; overflow-y:auto!important; overflow-x:hidden; overscroll-behavior:contain; touch-action:pan-y; scrollbar-width:thin; }
    #as-ix-shell .as-ix-card { display:flex; gap:10px; width:100%; min-height:88px; padding:10px; text-align:left; align-items:center; border-bottom:1px solid #292e35; }
    #as-ix-shell .as-ix-card:active { background:#313b4b; }
    #as-ix-shell .as-ix-card[aria-current=true] { border-left:3px solid #8dbbff; background:#222d3c; }
    #as-ix-shell img { width:88px; height:50px; object-fit:cover; border-radius:5px; flex:none; }
    #as-ix-shell .as-ix-text { min-width:0; }
    #as-ix-shell .as-ix-title { display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; font-size:18px; }
    #as-ix-shell .as-ix-channel { display:block; overflow:hidden; white-space:nowrap; text-overflow:ellipsis; font-size:14px; color:#b6bcc7; margin-top:5px; }
    #as-ix-shell .as-ix-empty { padding:20px 14px; color:#b6bcc7; }
    #as-ix-scroll-mode[aria-pressed=true] { color:#8dbbff!important; background:#223044!important; }
    #as-ix-pages { border-bottom:0; border-top:1px solid #363b43; }
  `;
  function videoUrl(raw) {
    try {
      var u = new URL(raw, location.href);
      if (u.protocol !== 'https:' || ['m.youtube.com','www.youtube.com','youtube.com'].indexOf(u.hostname) < 0 || u.pathname !== '/watch') return null;
      var id = u.searchParams.get('v');
      if (!id || !/^[A-Za-z0-9_-]{6,20}$/.test(id)) return null;
      var out = new URL('https://m.youtube.com/watch'); out.searchParams.set('v', id);
      ['list','index','t'].forEach(function(k){ var v=u.searchParams.get(k); if(v)out.searchParams.set(k,v); });
      return {url:out.href,id:id};
    } catch(ignore) { return null; }
  }
  function entries(kind) {
    var selector = kind === 'playlist' ? 'ytm-playlist-panel-renderer,ytd-playlist-panel-renderer' :
      'ytm-watch-next-secondary-results-renderer,ytd-watch-next-secondary-results-renderer,#related';
    var roots = Array.from(document.querySelectorAll(selector));
    if (!roots.length && kind === 'recommend') roots = [document.querySelector('ytm-watch,ytd-watch-flexy') || document.body];
    var seen = Object.create(null), rows = [], current = new URL(location.href).searchParams.get('v');
    roots.forEach(function(root){
      root.querySelectorAll('a[href]').forEach(function(a){
        if (shell && shell.contains(a)) return;
        if (kind === 'recommend' && a.closest('ytm-playlist-panel-renderer,ytd-playlist-panel-renderer,#comments')) return;
        var item=videoUrl(a.href); if(!item || (kind==='recommend' && item.id===current)) return;
        var key=kind==='recommend'?item.id:item.url; if(seen[key])return;
        var card=a.closest('ytm-video-with-context-renderer,ytm-compact-video-renderer,ytm-playlist-panel-video-renderer,ytd-compact-video-renderer,ytd-playlist-panel-video-renderer,yt-lockup-view-model,ytm-rich-item-renderer,ytd-rich-item-renderer') || a;
        var heading=card.querySelector('#video-title,h3,h4,.media-item-headline,.title');
        var title=(heading && heading.textContent) || a.getAttribute('title') || a.getAttribute('aria-label') || a.textContent;
        title=(title||'').trim(); if(!title)return;
        var byline=card.querySelector('#channel-name,.media-item-byline,.byline,ytm-badge-and-byline-renderer');
        rows.push({url:item.url,id:item.id,title:title.slice(0,250),channel:(byline?byline.textContent:'').trim().slice(0,100)});
        seen[key]=true;
      });
    });
    return rows.slice(0,100);
  }
  function button(label, action) {
    var b=document.createElement('button'); b.type='button'; b.textContent=label;
    b.addEventListener('click',action); return b;
  }
  function selectPane(value) {
    window.__asIxScrollPane=value;
    if(scrollButton){scrollButton.textContent=value==='list'?'清單操作':'影片操作';scrollButton.setAttribute('aria-pressed',String(value==='list'));}
  }
  function createShell() {
    if(shell)return;
    shell=document.createElement('div'); shell.id='as-ix-shell';
    var side=document.createElement('aside'); side.id='as-ix-side'; side.setAttribute('aria-label','選片清單');
    var tabs=document.createElement('div');tabs.id='as-ix-tabs';tabs.setAttribute('role','tablist');
    [['recommend','推薦'],['playlist','播放清單']].forEach(function(pair){
      var b=button(pair[1],function(){tab=pair[0];signature='';render();list.scrollTop=0;});
      b.dataset.tab=pair[0];b.setAttribute('role','tab');tabs.appendChild(b);
    });
    list=document.createElement('div');list.id='as-ix-list';list.setAttribute('role','list');
    var pages=document.createElement('div');pages.id='as-ix-pages';
    pages.appendChild(button('↑ 上一頁',function(){list.scrollTop-=Math.max(76,list.clientHeight*0.85);}));
    scrollButton=button('影片操作',function(){selectPane(window.__asIxScrollPane==='list'?'player':'list');});
    scrollButton.id='as-ix-scroll-mode';scrollButton.setAttribute('aria-label','切換滑動操作區域');pages.appendChild(scrollButton);
    pages.appendChild(button('下一頁 ↓',function(){list.scrollTop+=Math.max(76,list.clientHeight*0.85);}));
    side.append(tabs,list,pages);shell.appendChild(side);document.body.appendChild(shell);
  }
  function removeShell() {
    if(shell)shell.remove();shell=null;list=null;signature='';scrollButton=null;selectPane('player');
  }
  function cleanup() {
    window.__asIxActive=false;selectPane('player');
    document.documentElement.classList.remove('as-ix','as-ix-fullscreen');
    document.querySelectorAll('[data-as-ix-player],[data-as-ix-ancestor],[data-as-ix-inner]').forEach(function(n){n.removeAttribute('data-as-ix-player');n.removeAttribute('data-as-ix-ancestor');n.removeAttribute('data-as-ix-inner');});
    removeShell();player=null;
  }
  function render() {
    var id=new URL(location.href).searchParams.get('v');
    var fullscreenRoot=document.fullscreenElement;
    var full=!!fullscreenRoot || window.__asIxNativeFullscreen===true;
    var video=fullscreenRoot ? (fullscreenRoot.matches('video')?fullscreenRoot:fullscreenRoot.querySelector('video')) : document.querySelector('video');
    var wide=window.__asIxEnabled!==false && location.pathname==='/watch' && id && innerWidth>=640 && innerHeight>=240;
    if((!wide && !full) || !video){cleanup();return;}
    var root=fullscreenRoot || document.querySelector('#player-container-id,ytd-player,#player-container,ytm-player') || video.closest('#movie_player') || video.parentElement;
    if(!root || root===document.body){cleanup();return;}
    if(player && player!==root)cleanup();
    player=root;
    if(!style){style=document.createElement('style');style.id='as-ix-style';style.textContent=css;document.head.appendChild(style);}
    document.documentElement.style.setProperty('--as-ix-side','clamp(240px,30vw,400px)');
    window.__asIxActive=!full;
    document.documentElement.classList.add('as-ix');
    document.documentElement.classList.toggle('as-ix-fullscreen',full);
    player.setAttribute('data-as-ix-player','');
    for(var inner=video===player?null:video.parentElement;inner && inner!==player;inner=inner.parentElement)inner.setAttribute('data-as-ix-inner','');
    for(var p=player.parentElement;p && p!==document.body;p=p.parentElement)p.setAttribute('data-as-ix-ancestor','');
    if(full){removeShell();return;}
    createShell();
    shell.querySelectorAll('[data-tab]').forEach(function(b){b.setAttribute('aria-selected',String(b.dataset.tab===tab));});
    var rows=entries(tab), sig=tab+'|'+id+'|'+JSON.stringify(rows);
    if(sig===signature)return; signature=sig;
    var old=list.scrollTop;list.replaceChildren();
    if(!rows.length){var empty=document.createElement('div');empty.className='as-ix-empty';empty.textContent=tab==='playlist'?'目前沒有播放清單':'尚未取得推薦，仍可從手機選片';list.appendChild(empty);}
    rows.forEach(function(row){
      var b=button('',function(){location.assign(row.url);});b.className='as-ix-card';b.setAttribute('role','listitem');
      b.setAttribute('aria-current',String(row.id===id));
      var image=document.createElement('img');image.src='https://i.ytimg.com/vi/'+row.id+'/mqdefault.jpg';image.alt='';image.loading='lazy';
      var text=document.createElement('span');text.className='as-ix-text';
      var title=document.createElement('span');title.className='as-ix-title';title.textContent=row.title;
      var channel=document.createElement('span');channel.className='as-ix-channel';channel.textContent=row.channel;
      text.append(title,channel);b.append(image,text);list.appendChild(b);
    });
    list.scrollTop=lastWatch===id?old:0;lastWatch=id;
  }
  function schedule(){if(timer!==null)return;timer=setTimeout(function(){timer=null;render();},450);}
  function start(){
    var viewport=document.querySelector('meta[name=viewport]');
    if(viewport)viewport.content='width=device-width,initial-scale=1';
    new MutationObserver(function(records){if(records.some(function(r){return !shell || !shell.contains(r.target);}))schedule();})
      .observe(document.body,{childList:true,subtree:true});
    document.addEventListener('click',function(e){
      if(!window.__asIxActive || (scrollButton && scrollButton.contains(e.target)))return;
      if(shell && shell.contains(e.target))selectPane('list');
      else if(player && player.contains(e.target))selectPane('player');
    },true);
    render();setInterval(schedule,2000);
  }
  window.__asIxLayout={refresh:schedule,setEnabled:function(value){window.__asIxEnabled=value;render();},setFullscreen:function(value){window.__asIxNativeFullscreen=value;render();}};
  ['resize','popstate','yt-navigate-finish','state-navigateend','fullscreenchange'].forEach(function(e){window.addEventListener(e,schedule);});
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',start,{once:true});else start();
})();
