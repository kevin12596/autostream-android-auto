package com.kcliang.autostream

/** Host scroll events have deltas but no pointer origin; prefer nested lists over the root page. */
object NestedScrollJs {
    fun command(dx: Float, dy: Float, anchorX: Float?, anchorY: Float?): String =
        "($SCRIPT)($dx,$dy,${anchorX ?: "null"},${anchorY ?: "null"});"

    private val SCRIPT = """
        function(dx, dy, ax, ay) {
          if (!Number.isFinite(dx) || !Number.isFinite(dy)) return false;
          var w = window.innerWidth, h = window.innerHeight;
          function usable(n) {
            if (!n || n === document.body || n === document.documentElement) return false;
            var r = n.getBoundingClientRect();
            if (r.width <= 0 || r.height <= 0 || r.bottom <= 0 || r.top >= h) return false;
            var s = window.getComputedStyle(n);
            return n.scrollHeight > n.clientHeight + 2 && n.clientHeight > 0 && /auto|scroll|overlay/.test(s.overflowY);
          }
          var pageCandidate = null;
          function parent(n) {
            while (n && n !== document.body && n !== document.documentElement) {
              if (usable(n)) {
                if (n.getBoundingClientRect().width < w*0.9) return n;
                if (!pageCandidate) pageCandidate = n;
              }
              n = n.parentElement;
            }
            return null;
          }
          function at(x, y) {
            var hits = document.elementsFromPoint ? document.elementsFromPoint(x,y) : [document.elementFromPoint(x,y)];
            for (var i = 0; i < hits.length; i++) { var p = parent(hits[i]); if (p) return p; }
            return null;
          }
          var fixedList = document.getElementById && document.getElementById('as-ix-list');
          var wide = fixedList && document.documentElement.classList && document.documentElement.classList.contains('as-ix');
          if (wide && window.__asIxScrollPane !== 'list') return false;
          var original = window.__asIxEnabled === false || window.__asIxActive === false;
          var target = wide ? fixedList : null;
          if (!target && original) target = at(w*0.35,h*0.5) || pageCandidate || document.scrollingElement || document.documentElement;
          if (!target) target = Number.isFinite(ax) && Number.isFinite(ay) ? at(ax*w, ay*h) : null;
          if (!target) target = at(w*0.86, h*0.55);
          if (!target) {
            var panels = document.querySelectorAll('ytm-playlist-panel-renderer,ytd-playlist-panel-renderer,#playlist');
            for (var i = 0; i < panels.length && !target; i++) {
              var r = panels[i].getBoundingClientRect();
              if (r.left < w*0.45 || r.width > w*0.65 || r.bottom <= 0 || r.top >= h) continue;
              if (usable(panels[i])) { target = panels[i]; break; }
              var nodes = panels[i].querySelectorAll('*');
              for (var j = 0; j < nodes.length; j++) if (usable(nodes[j])) { target = nodes[j]; break; }
            }
          }
          if (!target) target = pageCandidate || document.scrollingElement || document.documentElement;
          if (!target) return false;
          // Clamp within the selected list; reaching its edge must not chain to the page.
          target.scrollTop = Math.max(0, Math.min(target.scrollHeight-target.clientHeight, target.scrollTop+dy));
          target.scrollLeft = Math.max(0, Math.min(Math.max(0,target.scrollWidth-target.clientWidth), target.scrollLeft+dx));
          return true;
        }
    """.trimIndent()
}
