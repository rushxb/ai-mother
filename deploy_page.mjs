import { Client } from 'file:///C:/Users/rush/.codex/skills/gstack/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import { StdioClientTransport } from 'file:///C:/Users/rush/.codex/skills/gstack/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js';

const body = `<div style="min-height:100vh;display:flex;align-items:center;justify-content:center;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:linear-gradient(135deg,#0f0c29,#302b63,#24243e);color:#fff;margin:0;padding:0">
  <div style="text-align:center;padding:3rem 4rem;background:rgba(255,255,255,0.06);border-radius:16px;backdrop-filter:blur(12px);border:1px solid rgba(255,255,255,0.1);box-shadow:0 8px 32px rgba(0,0,0,0.3)">
    <h1 style="font-size:2.4rem;margin-bottom:0.6rem;background:linear-gradient(90deg,#f7971e,#ffd200);-webkit-background-clip:text;-webkit-text-fill-color:transparent">Hello, World!</h1>
    <p style="font-size:1.1rem;color:rgba(255,255,255,0.7);line-height:1.6">This page was created and deployed by Codex.</p>
    <p style="margin-top:1.5rem;font-size:0.9rem;color:rgba(255,255,255,0.4)" id="time"></p>
  </div>
</div>`;

const scriptContent = `function updateTime(){document.getElementById('time').textContent=new Date().toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'})}updateTime();setInterval(updateTime,1000);`;

const serverPath = 'C:/Users/rush/AppData/Local/npm-cache/_npx/23b8816622ee9a15/node_modules/mcp-html-sync-server/dist/server.js';

const transport = new StdioClientTransport({
  command: 'node',
  args: [serverPath],
  env: {
    ...process.env,
    BASE_URL: 'https://mcp.rushxb.top/pages',
    NODE_ENV: 'production',
    SERVER_PORT: '0',
    PAGE_MAX_AGE: '15d',
    PAGE_MAX_COUNT: '200'
  }
});

const client = new Client({ name: 'codex-deploy', version: '1.0.0' });

try {
  await client.connect(transport);
  console.log('Connected to MCP server');

  const result = await client.callTool({
    name: 'create_page',
    arguments: {
      body: body,
      scripts: [{ content: scriptContent }]
    }
  });

  console.log('Result:', JSON.stringify(result, null, 2));
} catch (err) {
  console.error('Error:', err.message);
  console.error('Stack:', err.stack);
} finally {
  await client.close();
  process.exit(0);
}
