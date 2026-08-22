'use strict';

// A deliberately primitive page: no flexbox, no fetch, no ES6, nothing a 2015
// e-ink browser might choke on. If the probe itself fails to render we learn
// nothing, so it uses only features far below the ones it tests for.

function renderProbePage() {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Cache-Control" content="no-cache">
<title>Probe</title>
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
  font-family: Georgia, "Times New Roman", serif;
  font-size: 30px;
  line-height: 1.45;
  color: #000;
  background: #fff;
  padding: 22px 26px 90px;
}
h1 { font-size: 34px; letter-spacing: 0.2em; text-transform: uppercase; font-weight: normal; }
p.lede { color: #555; font-size: 24px; margin: 4px 0 18px; }
h2 {
  font-size: 21px;
  letter-spacing: 0.26em;
  text-transform: uppercase;
  font-weight: normal;
  color: #555;
  margin: 22px 0 6px;
  border-bottom: 2px solid #000;
  padding-bottom: 5px;
}
table { width: 100%; border-collapse: collapse; }
td { padding: 9px 0; border-bottom: 1px solid #ddd; vertical-align: top; }
td.k { width: 54%; }
td.v { font-family: "Courier New", monospace; font-weight: bold; }
td.v.no { font-weight: normal; color: #777; }
#ua { font-family: "Courier New", monospace; font-size: 19px; word-wrap: break-word; color: #333; }
#edge {
  position: absolute; left: 0; right: 0;
  border-top: 4px solid #000;
  font-size: 19px; letter-spacing: 0.2em; text-transform: uppercase;
  background: #eee; padding: 4px 8px;
}
#swatch div { background: #000; height: 14px; margin-bottom: 2px; }
#swatch span { font-size: 18px; display: block; margin-bottom: 6px; }
a.btn {
  display: block;
  text-align: center;
  border: 4px solid #000;
  padding: 24px 12px;
  margin: 16px 0;
  font-size: 28px;
  letter-spacing: 0.2em;
  text-transform: uppercase;
  text-decoration: none;
  color: #000;
}
a.btn:active { background: #000; color: #fff; }
#tbverdict { font-size: 32px; font-weight: bold; margin-top: 12px; }
</style>
</head>
<body>

<h1>Kindle probe</h1>
<p class="lede">Read these values back &mdash; they tune the layout to this screen.</p>
<div id="out">JavaScript did not run.</div>

<script>
(function () {
  var rows = [];
  var de = document.documentElement;

  function sec(name) { rows.push(['H2', name, '']); }
  function row(k, v) { rows.push(['TD', k, v, false]); }
  // css() returns true, false, or a string naming the prefix it needed.
  function yn(k, ok, note) {
    var extra = typeof ok === 'string' ? ' ' + ok : (note ? ' ' + note : '');
    rows.push(['TD', k, (ok ? 'yes' : 'NO') + extra, !ok]);
  }

  /* ---------- geometry ---------- */

  sec('Viewport');
  row('documentElement client', de.clientWidth + ' x ' + de.clientHeight);
  row('window inner', (window.innerWidth || 0) + ' x ' + (window.innerHeight || 0));
  row('screen', screen.width + ' x ' + screen.height);
  row('devicePixelRatio', String(window.devicePixelRatio || 1));
  row('document scrollHeight', String(de.scrollHeight));
  row('forced scroll?', de.scrollHeight > de.clientHeight
    ? 'YES by ' + (de.scrollHeight - de.clientHeight) + 'px'
    : 'no');
  row('orientation', de.clientWidth > de.clientHeight ? 'landscape' : 'portrait');

  /* ---------- css ---------- */

  // Compare against '' rather than the value we set: browsers normalise
  // ("calc(10px + 5px)" comes back as "calc(15px)"), so an equality test
  // reports false negatives. A rejected value leaves the property empty.
  // Vendor-prefixed spellings count, and we report which one was needed.
  function css(prop, values) {
    if (typeof values === 'string') values = [values];
    var el = document.createElement('div');
    var cap = prop.charAt(0).toUpperCase() + prop.slice(1);
    var props = [prop, 'Webkit' + cap, 'Moz' + cap, 'ms' + cap];
    for (var p = 0; p < props.length; p++) {
      if (!(props[p] in el.style)) continue;
      for (var v = 0; v < values.length; v++) {
        try {
          el.style[props[p]] = '';
          el.style[props[p]] = values[v];
        } catch (e) { continue; }
        if (el.style[props[p]] !== '') {
          var prefixed = props[p] !== prop || values[v] !== values[0];
          return prefixed ? '(' + values[v] + ' via ' + props[p] + ')' : true;
        }
      }
    }
    return false;
  }

  // position:fixed can parse and still lay out as static, so test the layout:
  // a fixed child of an offset parent must land at viewport x=0.
  function fixedWorks() {
    if (!de.getBoundingClientRect) return false;
    var box = document.createElement('div');
    box.style.position = 'relative';
    box.style.left = '40px';
    box.style.top = '40px';
    var inner = document.createElement('div');
    inner.style.position = 'fixed';
    inner.style.left = '0';
    inner.style.top = '0';
    inner.style.width = '4px';
    inner.style.height = '4px';
    box.appendChild(inner);
    document.body.appendChild(box);
    var left = inner.getBoundingClientRect().left;
    document.body.removeChild(box);
    return left === 0;
  }

  sec('CSS');
  yn('display: flex', css('display', ['flex', '-webkit-flex', '-webkit-box']));
  yn('display: grid', css('display', ['grid', '-ms-grid']));
  yn('display: table-cell', css('display', 'table-cell'));
  yn('position: fixed', fixedWorks(), '(layout)');
  yn('position: sticky', css('position', ['sticky', '-webkit-sticky']));
  yn('calc()', css('width', 'calc(10px + 5px)'));
  yn('vh units', css('height', '10vh'));
  yn('filter', css('filter', 'invert(1)'));
  yn('border-radius', css('borderRadius', '4px'));
  yn('transition', css('transition', 'all 1s'));
  yn('text-overflow', css('textOverflow', 'ellipsis'));

  /* ---------- js ---------- */

  sec('JavaScript');
  yn('XMLHttpRequest', typeof XMLHttpRequest !== 'undefined');
  yn('fetch', typeof window.fetch === 'function');
  yn('JSON', typeof JSON === 'object');
  yn('classList', 'classList' in de);
  yn('querySelector', typeof document.querySelector === 'function');
  yn('addEventListener', typeof document.addEventListener === 'function');
  yn('element.scrollTo', typeof de.scrollTo === 'function');
  yn('getBoundingClientRect', typeof de.getBoundingClientRect === 'function');

  var ls = false;
  try {
    localStorage.setItem('probe', '1');
    localStorage.removeItem('probe');
    ls = true;
  } catch (e) {}
  yn('localStorage', ls);

  yn('inline SVG', !!(document.createElementNS &&
    document.createElementNS('http://www.w3.org/2000/svg', 'svg').createSVGRect));

  var loc = '(threw)';
  try {
    loc = new Date().toLocaleDateString(undefined,
      { weekday: 'long', month: 'long', day: 'numeric' });
  } catch (e) {}
  row('toLocaleDateString(opts)', loc);

  /* ---------- paint ---------- */

  var html = '';
  for (var i = 0; i < rows.length; i++) {
    var r = rows[i];
    if (r[0] === 'H2') {
      html += (i ? '</table>' : '') + '<h2>' + r[1] + '</h2><table>';
    } else {
      html += '<tr><td class="k">' + r[1] + '</td>' +
        '<td class="v' + (r[3] ? ' no' : '') + '">' + r[2] + '</td></tr>';
    }
  }
  html += '</table>';
  html += '<h2>Server</h2><table><tr><td class="k">/api/poll</td>' +
    '<td class="v" id="net">testing&hellip;</td></tr></table>';
  html += '<h2>User agent</h2><div id="ua"></div>';
  html += '<h2>Width check</h2><p class="lede">The widest bar that still fits ' +
    'without a sideways scroll is the real width.</p><div id="swatch"></div>';
  html += '<h2>Toolbar test</h2><p class="lede">Some browsers hide their own ' +
    'bar once the page scrolls. If this one does, the viewport gets taller, ' +
    'and that is measurable. Tap below and watch: blank space appears, the ' +
    'page scrolls past it, then the verdict lands here. Takes 4 seconds.</p>' +
    '<a href="#" class="btn" id="tbtest">Tap to test</a><div id="tbresult"></div>';

  document.getElementById('out').innerHTML = html;
  document.getElementById('ua').appendChild(document.createTextNode(navigator.userAgent));

  var widths = [400, 600, 758, 900, 1072, 1200];
  var sw = document.getElementById('swatch');
  for (var w = 0; w < widths.length; w++) {
    var bar = document.createElement('div');
    bar.style.width = widths[w] + 'px';
    var lab = document.createElement('span');
    lab.appendChild(document.createTextNode(widths[w] + 'px'));
    sw.appendChild(bar);
    sw.appendChild(lab);
  }

  /* ---------- toolbar test ----------
     The scroll-to-hide trick that worked on old Android: give the page room
     to scroll, scroll it, and see whether the browser gave the chrome's space
     back. A taller viewport afterwards is proof it worked; anything else is
     proof it did not. This is the only way to settle it without the device. */

  function scrollY() {
    return Math.round(window.pageYOffset || de.scrollTop || document.body.scrollTop || 0);
  }

  // window.scrollTo is ancient and should exist, but set the scrollTop
  // properties too in case this browser only honours one of them.
  function scrollToY(y) {
    try { window.scrollTo(0, y); } catch (e) {}
    try { de.scrollTop = y; } catch (e) {}
    try { document.body.scrollTop = y; } catch (e) {}
  }

  function topOf(el) {
    var y = 0;
    while (el) { y += el.offsetTop; el = el.offsetParent; }
    return y;
  }

  var btn = document.getElementById('tbtest');

  btn.onclick = function (ev) {
    if (ev && ev.preventDefault) ev.preventDefault();
    btn.onclick = null;

    var beforeClient = de.clientHeight;
    var beforeInner = window.innerHeight || 0;
    var result = document.getElementById('tbresult');

    // Step 1: a spacer you can actually see. A white block on a white page
    // looks identical to nothing happening, which is what made the first
    // version of this test unreadable.
    var spacer = document.createElement('div');
    spacer.style.height = '600px';
    spacer.style.background = '#e4e4e4';
    spacer.style.border = '4px dashed #888';
    spacer.style.textAlign = 'center';
    spacer.style.paddingTop = '260px';
    spacer.style.fontSize = '26px';
    spacer.style.letterSpacing = '0.2em';
    spacer.appendChild(document.createTextNode('600px OF BLANK SPACE'));
    document.body.insertBefore(spacer, document.body.firstChild);

    btn.innerHTML = 'Step 1 &mdash; space added';
    result.innerHTML = '<p class="lede">Blank space is now above the page. ' +
      'Scrolling past it in 2 seconds&hellip;</p>';

    // Step 2: scroll past the spacer.
    setTimeout(function () {
      scrollToY(600);
      btn.innerHTML = 'Step 2 &mdash; scrolled, measuring';
      result.innerHTML = '<p class="lede">Scrolled. Measuring in 2 seconds&hellip;</p>';

      // Step 3: measure, come back, report.
      setTimeout(function () {
        var afterClient = de.clientHeight;
        var afterInner = window.innerHeight || 0;
        var y = scrollY();
        var gain = afterClient - beforeClient;

        var verdict;
        if (gain > 10) verdict = 'IT WORKS &mdash; gained ' + gain + 'px';
        else if (y === 0) verdict = 'the page would not scroll at all';
        else verdict = 'scrolled ' + y + 'px, but the bar stayed';

        result.innerHTML = '<table>' +
          '<tr><td class="k">client height before</td><td class="v">' + beforeClient + '</td></tr>' +
          '<tr><td class="k">client height after</td><td class="v">' + afterClient + '</td></tr>' +
          '<tr><td class="k">inner height before</td><td class="v">' + beforeInner + '</td></tr>' +
          '<tr><td class="k">inner height after</td><td class="v">' + afterInner + '</td></tr>' +
          '<tr><td class="k">scrolled to</td><td class="v">' + y + 'px</td></tr>' +
          '</table><div id="tbverdict">' + verdict + '</div>' +
          '<a class="btn" href="/probe">Start over</a>';
        btn.innerHTML = 'Done &mdash; read below';

        // Bring the verdict back into view; we scrolled away from it.
        var back = topOf(document.getElementById('tbtest')) - 40;
        scrollToY(back > 0 ? back : 0);
      }, 2000);
    }, 2000);
  };

  // A rule drawn where the browser claims the fold is. If it does not sit at
  // the bottom edge of the screen, the reported height is not the usable one.
  var edge = document.createElement('div');
  edge.id = 'edge';
  edge.style.top = (de.clientHeight - 26) + 'px';
  edge.appendChild(document.createTextNode('fold at ' + de.clientHeight + 'px'));
  document.body.appendChild(edge);

  var t0 = new Date().getTime();
  var xhr = new XMLHttpRequest();
  xhr.open('GET', '/api/poll?v=0&for=probe', true);
  xhr.setRequestHeader('Accept', 'application/json');
  xhr.onreadystatechange = function () {
    if (xhr.readyState !== 4) return;
    var ms = new Date().getTime() - t0;
    document.getElementById('net').innerHTML =
      (xhr.status === 200 ? 'ok' : 'FAILED ' + xhr.status) + ' in ' + ms + ' ms';
  };
  xhr.send(null);
})();
</script>

</body>
</html>`;
}

module.exports = { renderProbePage };
