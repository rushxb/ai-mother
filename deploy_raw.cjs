const { spawn } = require('child_process');

const serverPath = 'C:\\Users\\rush\\AppData\\Local\\npm-cache\\_npx\\23b8816622ee9a15\\node_modules\\mcp-html-sync-server\\dist\\server.js';

const body = '<div style="min-height:100vh;display:flex;align-items:center;justify-content:center;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;background:linear-gradient(135deg,#0f0c29,#302b63,#24243e);color:#fff;margin:0;padding:0"><div style="text-align:center;padding:3rem 4rem;background:rgba(255,255,255,0.06);border-radius:16px;backdrop-filter:blur(12px);border:1px solid rgba(255,255,255,0.1);box-shadow:0 8px 32px rgba(0,0,0,0.3)"><h1 style="font-size:2.4rem;margin-bottom:0.6rem;background:linear-gradient(90deg,#f7971e,#ffd200);-webkit-background-clip:text;-webkit-text-fill-color:transparent">Hello, World!</h1><p style="font-size:1.1rem;color:rgba(255,255,255,0.7);line-height:1.6">This page was created and deployed by Codex.</p><p style="margin-top:1.5rem;font-size:0.9rem;color:rgba(255,255,255,0.4)" id="time"></p></div></div>';

const scriptContent = 'function updateTime(){document.getElementById("time").textContent=new Date().toLocaleString("zh-CN",{timeZone:"Asia/Shanghai"})}updateTime();setInterval(updateTime,1000);';

const proc = spawn('node', [serverPath], {
  env: {
    BASE_URL: 'https://mcp.rushxb.top/pages',
    NODE_ENV: 'production',
    SERVER_PORT: '0',
    PAGE_MAX_AGE: '15d',
    PAGE_MAX_COUNT: '200',
    PATH: process.env.PATH
  },
  stdio: ['pipe', 'pipe', 'pipe']
});

let buf = '';
proc.stdout.on('data', d => { buf += d.toString(); });
proc.stderr.on('data', d => { /* ignore server logs */ });

function send(obj) {
  const json = JSON.stringify(obj);
  const msg = 'Content-Length: ' + Buffer.byteLength(json) + '\r\n\r\n' + json;
  proc.stdin.write(msg);
}

// Wait for server to start, then send MCP protocol messages
setTimeout(() => send({ jsonrpc:'2.0', id:1, method:'initialize', params:{ protocolVersion:'2024-11-05', capabilities:{}, clientInfo:{name:'codex',version:'1.0.0'} }}), 1000);
setTimeout(() => send({ jsonrpc:'2.0', method:'notifications/initialized' }), 2000);
setTimeout(() => send({ jsonrpc:'2.0', id:2, method:'tools/call', params:{ name:'create_page', arguments:{ body, scripts:[{content:scriptContent}] } }}), 3000);

// Collect and parse responses
setTimeout(() => {
  const results = [];
  let tmp = buf;
  while (true) {
    const idx = tmp.indexOf('\r\n\r\n');
    if (idx === -1) break;
    const hdr = tmp.slice(0, idx);
    const m = hdr.match(/Content-Length:\s*(\d+)/i);
    if (!m) break;
    const len = parseInt(m[1]);
    const start = idx + 4;
    if (tmp.length < start + len) break;
    try { results.push(JSON.parse(tmp.slice(start, start + len))); } catch(e) {}
    tmp = tmp.slice(start + len);
  }
  console.log(JSON.stringify(results, null, 2));
  proc.kill();
  process.exit(0);
}, 6000);
