const { spawn } = require('child_process');
const path = require('path');

const serverPath = path.join('C:\\Users\\rush\\AppData\\Local\\npm-cache\\_npx\\23b8816622ee9a15\\node_modules\\mcp-html-sync-server\\dist\\server.js');

const body = `<div style="min-height:100vh;display:flex;align-items:center;justify-content:center;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:linear-gradient(135deg,#0f0c29,#302b63,#24243e);color:#fff;margin:0;padding:0">
  <div style="text-align:center;padding:3rem 4rem;background:rgba(255,255,255,0.06);border-radius:16px;backdrop-filter:blur(12px);border:1px solid rgba(255,255,255,0.1);box-shadow:0 8px 32px rgba(0,0,0,0.3)">
    <h1 style="font-size:2.4rem;margin-bottom:0.6rem;background:linear-gradient(90deg,#f7971e,#ffd200);-webkit-background-clip:text;-webkit-text-fill-color:transparent">Hello, World!</h1>
    <p style="font-size:1.1rem;color:rgba(255,255,255,0.7);line-height:1.6">This page was created and deployed by Codex.</p>
    <p style="margin-top:1.5rem;font-size:0.9rem;color:rgba(255,255,255,0.4)" id="time"></p>
  </div>
</div>`;

const scriptContent = `function updateTime(){document.getElementById('time').textContent=new Date().toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'})}updateTime();setInterval(updateTime,1000);`;

const proc = spawn('node', [serverPath], {
  env: {
    ...process.env,
    BASE_URL: 'https://mcp.rushxb.top/pages',
    NODE_ENV: 'production',
    SERVER_PORT: '3001',
    PAGE_MAX_AGE: '15d',
    PAGE_MAX_COUNT: '200'
  },
  stdio: ['pipe', 'pipe', 'pipe']
});

let stdoutBuf = '';
let stderrBuf = '';
let responses = [];

proc.stdout.on('data', (d) => {
  stdoutBuf += d.toString();
  // Parse MCP responses (Content-Length framed JSON-RPC)
  while (true) {
    const headerEnd = stdoutBuf.indexOf('\r\n\r\n');
    if (headerEnd === -1) break;
    const header = stdoutBuf.substring(0, headerEnd);
    const match = header.match(/Content-Length:\s*(\d+)/i);
    if (!match) break;
    const len = parseInt(match[1]);
    const bodyStart = headerEnd + 4;
    if (stdoutBuf.length < bodyStart + len) break;
    const jsonStr = stdoutBuf.substring(bodyStart, bodyStart + len);
    stdoutBuf = stdoutBuf.substring(bodyStart + len);
    try {
      const msg = JSON.parse(jsonStr);
      responses.push(msg);
    } catch(e) {}
  }
});

proc.stderr.on('data', (d) => { stderrBuf += d.toString(); });

function sendMcp(id, method, params) {
  const msg = JSON.stringify({ jsonrpc: '2.0', id, method, params });
  const framed = 'Content-Length: ' + Buffer.byteLength(msg, 'utf8') + '\r\n\r\n' + msg;
  proc.stdin.write(framed);
}

setTimeout(() => {
  sendMcp(1, 'initialize', {
    protocolVersion: '2024-11-05',
    capabilities: {},
    clientInfo: { name: 'codex-deploy', version: '1.0.0' }
  });
}, 500);

setTimeout(() => {
  sendMcp(2, 'notifications/initialized', {});
}, 1000);

setTimeout(() => {
  sendMcp(3, 'tools/call', {
    name: 'create_page',
    arguments: {
      body: body,
      scripts: [{ content: scriptContent }]
    }
  });
}, 1500);

setTimeout(() => {
  console.log(JSON.stringify({ responses, stderr: stderrBuf }, null, 2));
  proc.kill();
  process.exit(0);
}, 4000);
