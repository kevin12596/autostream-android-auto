package com.kcliang.autostream

/**
 * JavaScript snippets injected into the YouTube page (m.youtube.com and www.youtube.com).
 *
 * Commands prefer the YouTube player API on `#movie_player` and fall back to the raw `<video>`.
 * State is reported back to the app over the `AutoStreamBridge` web message channel as JSON:
 * `{"t":"state","ev":..,"vid":..,"watch":bool,"playing":bool,"time":sec,"dur":sec,"ended":bool,"pfs":bool}`
 * (timeupdate throttled to 1/s).
 */
object YouTubeJs {

    const val QUALITY_MAX = "max"
    const val QUALITY_1080 = "hd1080"
    const val QUALITY_720 = "hd720"
    const val QUALITY_AUTO = "auto"
    private val CAPS = setOf(QUALITY_MAX, QUALITY_1080, QUALITY_720, QUALITY_AUTO)

    fun sanitizeCap(cap: String?): String = if (cap != null && cap in CAPS) cap else QUALITY_MAX

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{6,20}$")

    fun isValidVideoId(id: String?): Boolean = id != null && VIDEO_ID.matches(id)

    fun watchUrl(videoId: String, positionSec: Int): String {
        val host = "https://m.youtube.com"
        val t = positionSec.coerceAtLeast(0)
        return "$host/watch?v=$videoId" + if (t > 0) "&t=${t}s" else ""
    }

    /** Preserve saved paths, search queries and timestamps while upgrading desktop URLs. */
    fun mobileUrl(url: String): String =
        MOBILE_HOST.replaceFirst(url, "https://m.youtube.com")

    private val MOBILE_HOST = Regex("^https://(?:www\\.)?youtube\\.com(?=[:/?#]|$)", RegexOption.IGNORE_CASE)

    /** Document-start / page-finished hook. Idempotent per document. */
    fun hookScript(cap: String): String = "window.__asQualityCap='${sanitizeCap(cap)}';\n$HOOK"

    /** Live quality-cap change for the current document. */
    fun setQualityCap(cap: String): String =
        "window.__asQualityCap='${sanitizeCap(cap)}';if(window.__asApplyQuality)window.__asApplyQuality(true);"

    const val REQUEST_STATE = "if(window.__asState)window.__asState('poll');"

    val PAUSE_ALL =
        "document.querySelectorAll('video').forEach(function(v){try{v.pause()}catch(e){}});"

    val PLAY_ALL =
        "document.querySelectorAll('video').forEach(function(v){try{v.play()}catch(e){}});"

    fun doubleTapSeek(firstX: Float, firstY: Float, secondX: Float, secondY: Float, fullscreen: Boolean): String =
        "($DOUBLE_TAP_SEEK)($firstX,$firstY,$secondX,$secondY,$fullscreen);"

    private val DOUBLE_TAP_SEEK = """
        function(firstX, firstY, secondX, secondY, fullscreen) {
          if (![firstX, firstY, secondX, secondY].every(function(n){ return Number.isFinite(n) && n >= 0 && n <= 1; })) return false;
          var p = document.getElementById('movie_player');
          if (p && p.classList && (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting'))) return false;
          var v = (p && p.querySelector && p.querySelector('video')) || document.querySelector('video');
          if (!v || !Number.isFinite(v.duration) || v.duration <= 0 || !Number.isFinite(v.currentTime)) return false;
          var width = window.innerWidth, height = window.innerHeight;
          if (!(width > 0 && height > 0)) return false;
          var r = fullscreen ? { left: 0, top: 0, width: width, height: height } : v.getBoundingClientRect();
          if (!(r.width > 0 && r.height > 0)) return false;
          function sideAt(fx, fy) {
            var x = fx * width, y = fy * height;
            var rx = (x - r.left) / r.width, ry = (y - r.top) / r.height;
            if (ry < 0.18 || ry > 0.75) return 0;
            var hit = document.elementFromPoint(x, y);
            if ((!hit && !fullscreen) || (hit && hit.closest && hit.closest('a,button,input,textarea,select,[role="button"],[role="slider"],.ytp-chrome-top,.ytp-chrome-bottom,.ytp-progress-bar-container'))) return 0;
            if (rx >= 0.05 && rx <= 0.4) return -1;
            if (rx >= 0.6 && rx <= 0.95) return 1;
            return 0;
          }
          var side = sideAt(firstX, firstY);
          if (!side || sideAt(secondX, secondY) !== side) return false;
          try {
            // Changing currentTime preserves the existing playing/paused state.
            v.currentTime = Math.max(0, Math.min(v.duration, v.currentTime + side * 10));
          } catch(e) { return false; }
          if (window.__asState) window.__asState('seek');
          return true;
        }
    """.trimIndent()

    val TOGGLE_PLAY = """
        (function(){
          var p = document.getElementById('movie_player');
          try {
            if (p && p.getPlayerState && p.playVideo && p.pauseVideo) {
              var s = p.getPlayerState();
              if (s === 1 || s === 3) p.pauseVideo(); else p.playVideo();
              return;
            }
          } catch(e) {}
          var v = (p && p.querySelector && p.querySelector('video')) || document.querySelector('video');
          if (v) { try { if (v.paused || v.ended) v.play(); else v.pause(); } catch(e) {} }
        })();
    """.trimIndent()

    fun seekBy(deltaSec: Int): String = """
        (function(d){
          var p = document.getElementById('movie_player');
          try {
            if (p && p.getCurrentTime && p.seekTo) {
              var t = p.getCurrentTime() + d;
              var du = p.getDuration ? p.getDuration() : 0;
              if (t < 0) t = 0;
              if (du > 0 && t > du - 0.5) t = du - 0.5;
              p.seekTo(t, true);
              return;
            }
          } catch(e) {}
          var v = (p && p.querySelector && p.querySelector('video')) || document.querySelector('video');
          if (v) {
            try {
              var max = isFinite(v.duration) ? v.duration - 0.5 : 1e9;
              v.currentTime = Math.max(0, Math.min(max, v.currentTime + d));
            } catch(e) {}
          }
        })($deltaSec);
    """.trimIndent()

    val NEXT = """
        (function(){
          var href = location.href;
          var p = document.getElementById('movie_player');
          function clickNext(){
            var b = document.querySelector('.ytp-next-button');
            if (b) { try { b.click(); return true; } catch(e) {} }
            var a = document.querySelector(
              '#secondary ytd-compact-video-renderer a#thumbnail, #secondary yt-lockup-view-model a, ' +
              'ytm-video-with-context-renderer a, ytm-compact-video-renderer a');
            if (a) { try { a.click(); return true; } catch(e) {} }
            return false;
          }
          var used = false;
          try { if (p && p.nextVideo) { p.nextVideo(); used = true; } } catch(e) {}
          if (!used) { clickNext(); return; }
          setTimeout(function(){ if (location.href === href) clickNext(); }, 1200);
        })();
    """.trimIndent()

    /**
     * CSS "pseudo-fullscreen": requestFullscreen() from evaluateJavascript has no user activation,
     * so instead pin the player to the viewport. Ancestors are neutralised (transform/contain/
     * sticky create containing blocks / stacking contexts that would trap position:fixed).
     * Evaluates to the resulting state (true/false).
     */
    fun pseudoFullscreen(on: Boolean): String = """
        (function(on){
          var d = document.documentElement;
          if (!document.getElementById('__as_pfs_style')) {
            var s = document.createElement('style');
            s.id = '__as_pfs_style';
            s.textContent = ${jsString(PFS_CSS)};
            (document.head || d).appendChild(s);
          }
          document.querySelectorAll('.__as_pfs_anc').forEach(function(e){ e.classList.remove('__as_pfs_anc'); });
          document.querySelectorAll('.__as_pfs_target').forEach(function(e){ e.classList.remove('__as_pfs_target'); });
          if (on) {
            var p = document.getElementById('movie_player');
            if (!p) {
              var v = document.querySelector('video');
              if (v) p = v.closest('.html5-video-player') || v.parentElement;
            }
            if (!p) { d.classList.remove('__as_pfs'); return false; }
            p.classList.add('__as_pfs_target');
            for (var a = p.parentElement; a && a !== d; a = a.parentElement) a.classList.add('__as_pfs_anc');
            d.classList.add('__as_pfs');
          } else {
            d.classList.remove('__as_pfs');
          }
          var fire = function(){ try { window.dispatchEvent(new Event('resize')); } catch(e) {} };
          setTimeout(fire, 50); setTimeout(fire, 400);
          if (window.__asState) setTimeout(function(){ window.__asState('pfs'); }, 60);
          return d.classList.contains('__as_pfs');
        })($on);
    """.trimIndent()

    private val PFS_CSS = """
        html.__as_pfs, html.__as_pfs body { overflow: hidden !important; background: #000 !important; }
        html.__as_pfs .__as_pfs_anc {
          transform: none !important; filter: none !important; contain: none !important;
          will-change: auto !important; perspective: none !important; z-index: auto !important;
          position: static !important; overflow: visible !important; opacity: 1 !important;
          isolation: auto !important;
        }
        html.__as_pfs .__as_pfs_target {
          position: fixed !important; inset: 0 !important; top: 0 !important; left: 0 !important;
          right: 0 !important; bottom: 0 !important; width: 100vw !important; height: 100vh !important;
          max-width: none !important; max-height: none !important; min-height: 0 !important;
          margin: 0 !important; padding: 0 !important; transform: none !important;
          z-index: 2147483647 !important; background: #000 !important;
        }
        html.__as_pfs .__as_pfs_target video {
          object-fit: contain !important; width: 100% !important; height: 100% !important;
          top: 0 !important; left: 0 !important;
        }
        html.__as_pfs #masthead-container, html.__as_pfs ytd-masthead, html.__as_pfs #guide,
        html.__as_pfs tp-yt-app-drawer, html.__as_pfs ytm-mobile-topbar-renderer,
        html.__as_pfs #header-bar, html.__as_pfs ytm-pivot-bar-renderer,
        html.__as_pfs .mobile-topbar-header { display: none !important; }
    """.trimIndent()

    private fun jsString(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"

    private val HOOK = """
        (function(){
          if (window.__autostreamHooked) return;
          window.__autostreamHooked = true;
          function post(o){ try { if (window.AutoStreamBridge) window.AutoStreamBridge.postMessage(JSON.stringify(o)); } catch(e) {} }
          function player(){ return document.getElementById('movie_player'); }
          function video(){
            var p = player(), v = null;
            try { if (p && p.querySelector) v = p.querySelector('video'); } catch(e) {}
            return v || document.querySelector('video');
          }
          function isWatch(){ try { return location.pathname === '/watch'; } catch(e) { return false; } }
          function vid(){
            if (!isWatch()) return null;
            try { var q = new URLSearchParams(location.search).get('v'); if (q) return q; } catch(e) {}
            try { var p = player(); if (p && p.getVideoData) { var dd = p.getVideoData(); if (dd && dd.video_id) return dd.video_id; } } catch(e) {}
            return null;
          }
          function state(ev){
            var v = video();
            var o = { t: 'state', ev: ev, vid: vid(), watch: isWatch(),
                      pfs: document.documentElement.classList.contains('__as_pfs'),
                      playing: false, time: 0, dur: 0, ended: false };
            var data = {};
            try { var pp = player(); if (pp && pp.getVideoData) data = pp.getVideoData() || {}; } catch(e) {}
            o.title = String(data.title || document.title || 'YouTube').replace(/ - YouTube$/, '').slice(0,300);
            o.author = String(data.author || 'YouTube').slice(0,200);
            if (v) {
              var r = v.getBoundingClientRect(), w = window.innerWidth, h = window.innerHeight;
              if (w > 0 && h > 0 && r.width > 0 && r.height > 0) o.rect = [r.left / w, r.top / h, r.right / w, r.bottom / h];
              o.playing = !v.paused && !v.ended;
              o.time = v.currentTime || 0;
              o.dur = isFinite(v.duration) ? v.duration : 0;
              o.ended = !!v.ended;
            }
            post(o);
          }
          function isV(e){ return e && e.target && e.target.tagName === 'VIDEO'; }
          ['play','playing','pause','ended','loadeddata','durationchange','seeked','emptied'].forEach(function(n){
            document.addEventListener(n, function(e){
              if (!isV(e)) return;
              state(n);
              if (n === 'loadeddata' || n === 'playing') scheduleQuality(false);
            }, true);
          });
          var lastTu = 0;
          document.addEventListener('timeupdate', function(e){
            if (!isV(e)) return;
            var now = Date.now();
            if (now - lastTu < 1000) return;
            lastTu = now;
            state('timeupdate');
          }, true);
          function onNav(){ scheduleQuality(true); setTimeout(function(){ state('nav'); }, 300); }
          window.addEventListener('yt-navigate-finish', onNav);
          window.addEventListener('state-navigateend', onNav);
          var layoutTimer = null;
          function onLayout(){
            if (layoutTimer) return;
            layoutTimer = setTimeout(function(){ layoutTimer = null; state('layout'); }, 100);
          }
          document.addEventListener('scroll', onLayout, true);
          document.addEventListener('fullscreenchange', onLayout);
          window.addEventListener('resize', onLayout);

          var ORDER = ['highres','hd4320','hd2880','hd2160','hd1440','hd1080','hd720','large','medium','small','tiny'];
          function pickLevel(levels, cap){
            var lv = [];
            for (var i = 0; i < levels.length; i++) if (levels[i] && levels[i] !== 'auto') lv.push(levels[i]);
            if (!lv.length) return null;
            if (cap === 'max') return lv[0];
            var ci = ORDER.indexOf(cap);
            for (var j = 0; j < lv.length; j++) {
              var li = ORDER.indexOf(lv[j]);
              if (li >= 0 && li >= ci) return lv[j];
            }
            return lv[lv.length - 1];
          }
          function applyQuality(){
            var cap = window.__asQualityCap || 'max';
            var p = player();
            if (!p) return false;
            if (cap === 'auto') {
              try { if (p.setPlaybackQualityRange) p.setPlaybackQualityRange('auto', 'auto'); } catch(e) {}
              return true;
            }
            if (!p.getAvailableQualityLevels) return false;
            var q = pickLevel(p.getAvailableQualityLevels() || [], cap);
            if (!q) return false;
            try { if (p.setPlaybackQualityRange) p.setPlaybackQualityRange(q, q); } catch(e) {}
            try { if (p.setPlaybackQuality) p.setPlaybackQuality(q); } catch(e) {}
            return true;
          }
          var qTimer = null, qKey = null;
          function scheduleQuality(force){
            var key = (vid() || location.href) + '|' + (window.__asQualityCap || 'max');
            if (!force && key === qKey) return;
            qKey = key;
            if (qTimer) clearInterval(qTimer);
            var n = 0, ok = 0;
            qTimer = setInterval(function(){
              n++;
              var r = false;
              try { r = applyQuality(); } catch(e) {}
              if (r) ok++;
              if (ok >= 3 || n >= 12) { clearInterval(qTimer); qTimer = null; }
            }, 1000);
          }
          window.__asApplyQuality = scheduleQuality;
          window.__asState = state;
          if (document.readyState !== 'loading') scheduleQuality(true);
          else document.addEventListener('DOMContentLoaded', function(){ scheduleQuality(true); });
        })();
    """.trimIndent()
}
