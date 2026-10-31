// Reverse proxy for the Virtual Console. Injects a script that stops the page's speed
// integration unless a pedal is held, so API playback and the page do not fight.
// The patch relies on internal page names. If any is missing after a console update, the
// script shows a banner and leaves the page alone rather than half-patching it.
const http = require('http');
const TARGET = { host: '127.0.0.1', port: 18088 };
const PORT = 18089;
const INJECT = `<script>
window.addEventListener('load',()=>{
  // typeof, not window.x: pedals and friends may be top-level let/const in the page.
  const missing=[];
  if(typeof window.physicsTick!=='function')missing.push('physicsTick');
  if(typeof pedals==='undefined')missing.push('pedals');
  if(typeof pedalControl==='undefined')missing.push('pedalControl');
  if(typeof refresh!=='function')missing.push('refresh');
  if(missing.length){
    const banner=document.createElement('div');
    banner.id='console-proxy-banner';
    banner.textContent='Console proxy NOT applied, missing: '+missing.join(', ')+'. The page is unmodified and will fight API playback. Run the scenario without this page.';
    banner.style.cssText='position:fixed;top:0;left:0;right:0;z-index:2147483647;padding:12px 16px;background:#b00020;color:#fff;font:bold 16px sans-serif;text-align:center';
    document.body.appendChild(banner);
    console.error('[console-proxy] missing:',missing.join(', '));
    return;
  }
  const original=window.physicsTick;
  window.physicsTick=function(now){
    if(pedals.accelerator||pedals.brake||pedalControl.accelerator||pedalControl.brake){original(now);return;}
    lastFrame=now;requestAnimationFrame(window.physicsTick);
  };
  setInterval(refresh,200);
});
</script>`;

// Falls back to appending, so a page without </body> still gets the check and its banner.
function injectInto(html) {
  return html.includes('</body>') ? html.replace('</body>', () => INJECT + '</body>') : html + INJECT;
}

function createProxy(target = TARGET) {
  return http.createServer((req, res) => {
    const isPage = req.url === '/';
    const headers = { ...req.headers };
    // The page must arrive uncompressed for the script to be injected.
    if (isPage) delete headers['accept-encoding'];
    const up = http.request({ ...target, path: req.url, method: req.method, headers }, (r) => {
      if (isPage && (r.headers['content-type'] || '').startsWith('text/html')) {
        const chunks = [];
        r.on('data', (c) => chunks.push(c));
        r.on('end', () => {
          const html = injectInto(Buffer.concat(chunks).toString('utf8'));
          const h = { ...r.headers };
          delete h['content-length'];
          res.writeHead(r.statusCode, h);
          res.end(html);
        });
      } else {
        res.writeHead(r.statusCode, r.headers);
        r.pipe(res);
      }
    });
    up.on('error', () => { res.writeHead(502); res.end(); });
    req.pipe(up);
  });
}

if (require.main === module) {
  createProxy().listen(PORT, '127.0.0.1', () => console.log('proxy on', PORT));
}

module.exports = { createProxy, injectInto, INJECT };
