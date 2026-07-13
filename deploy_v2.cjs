const { spawn } = require('child_process');
const fs = require('fs');

const serverPath = 'C:\\Users\\rush\\AppData\\Local\\npm-cache\\_npx\\23b8816622ee9a15\\node_modules\\mcp-html-sync-server\\dist\\server.js';

const html = fs.readFileSync('html/index.html', 'utf-8');
const bodyMatch = html.match(/<body[^>]*>([\s\S]*?)<\/body>/i);
const scriptMatch = html.match(/<script[^>]*>([\s\S]*?)<\/script>/i);

const body = bodyMatch ? bodyMatch[1].trim() : html;
const scriptContent = scriptMatch ? scriptMatch[1].trim() : '';

console.log('Body length:', body.length);
console.log('Script length:', scriptContent.length);
console.log('Spawning server...');

const proc = spawn('node', [serverPath], {
  env: {
    ...process.env,
    BASE_URL: 'http://103.242.14.13:3000/',
    NODE_ENV: 'production',
    SERVER_HOST: '0.0.0.0',
    SERVER_PORT: '0',
    PAGE_MAX_AGE: '2h',
    PAGE_MAX_COUNT: '2000'
  },
  stdio: ['pipe', 'pipe', 'pipe']
});

let stdoutBuf = '';
let responses = [];

proc.stdout.on('data', (d) => {
  const text = d.toString();
  stdoutBuf += text;
  console.log('STDOUT:', text.substring(0, 500));
  const lines = stdoutBuf.split('\n');
  stdoutBuf = lines.pop();
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    try {
      const msg = JSON.parse(trimmed);
      console.log('Parsed:', JSON.stringify(msg).substring(0, 500));
      responses.push(msg);
    } catch(e) {}
  }
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
      responses.push(JSON.parse(jsonStr));
    } catch(e) {}
  }
});

proc.stderr.on('data', (d) => {
  console.log('STDERR:', d.toString().substring(0, 300));
});

function sendJson(obj) {
  const json = JSON.stringify(obj);
  console.log('Sending:', obj.method, obj.id || '');
  proc.stdin.write(json + '\n');
}

setTimeout(() => sendJson({
  jsonrpc: '2.0', id: 1,
  method: 'initialize',
  params: {
    protocolVersion: '2024-11-05',
    capabilities: {},
    clientInfo: { name: 'codex-deploy', version: '1.0.0' }
  }
}), 1500);

setTimeout(() => sendJson({
  jsonrpc: '2.0',
  method: 'notifications/initialized'
}), 3000);

setTimeout(() => {
  const args = { body };
  if (scriptContent) args.scripts = [{ content: scriptContent }];
  sendJson({
    jsonrpc: '2.0', id: 2,
    method: 'tools/call',
    params: { name: 'create_page', arguments: args }
  });
}, 4500);

setTimeout(() => {
  console.log('\n=== RESULT ===');
  console.log(JSON.stringify(responses, null, 2));
  proc.kill();
  process.exit(0);
}, 10000);
