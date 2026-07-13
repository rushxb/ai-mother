import { readFileSync } from 'fs';
import { Client } from 'file:///C:/Users/rush/.codex/skills/gstack/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js';
import { StdioClientTransport } from 'file:///C:/Users/rush/.codex/skills/gstack/node_modules/@modelcontextprotocol/sdk/dist/esm/stdio.js';

const html = readFileSync('html/index.html', 'utf-8');

const bodyMatch = html.match(/<body[^>]*>([\s\S]*?)<\/body>/i);
const scriptMatch = html.match(/<script[^>]*>([\s\S]*?)<\/script>/i);

const body = bodyMatch ? bodyMatch[1].trim() : html;
const scriptContent = scriptMatch ? scriptMatch[1].trim() : '';

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

  const args = { body };
  if (scriptContent) {
    args.scripts = [{ content: scriptContent }];
  }

  const result = await client.callTool({
    name: 'create_page',
    arguments: args
  });

  console.log('Result:', JSON.stringify(result, null, 2));
} catch (err) {
  console.error('Error:', err.message);
  console.error('Stack:', err.stack);
} finally {
  await client.close();
  process.exit(0);
}
