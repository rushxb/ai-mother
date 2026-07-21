'use strict';

const http = require('http');
const net = require('net');

const upstreamHost = process.argv[2];
const upstreamPort = Number(process.argv[3]);
const listenPort = Number(process.argv[4]);

if (!upstreamHost || !Number.isInteger(upstreamPort) || !Number.isInteger(listenPort)) {
  throw new Error('preview gateway requires upstream host, upstream port, and listen port');
}

const server = http.createServer((request, response) => {
  const upstream = http.request({
    host: upstreamHost,
    port: upstreamPort,
    method: request.method,
    path: request.url,
    headers: request.headers,
  }, (upstreamResponse) => {
    response.writeHead(upstreamResponse.statusCode || 502, upstreamResponse.headers);
    upstreamResponse.pipe(response);
  });
  upstream.setTimeout(30_000, () => upstream.destroy(new Error('upstream timeout')));
  upstream.on('error', () => {
    if (!response.headersSent) {
      response.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' });
    }
    response.end('preview upstream unavailable');
  });
  request.pipe(upstream);
});

server.on('upgrade', (request, clientSocket, head) => {
  const upstreamSocket = net.connect(upstreamPort, upstreamHost, () => {
    upstreamSocket.write(`${request.method} ${request.url} HTTP/${request.httpVersion}\r\n`);
    for (let index = 0; index < request.rawHeaders.length; index += 2) {
      upstreamSocket.write(`${request.rawHeaders[index]}: ${request.rawHeaders[index + 1]}\r\n`);
    }
    upstreamSocket.write('\r\n');
    if (head.length > 0) {
      upstreamSocket.write(head);
    }
    clientSocket.pipe(upstreamSocket).pipe(clientSocket);
  });
  upstreamSocket.setTimeout(30_000, () => upstreamSocket.destroy());
  upstreamSocket.on('error', () => clientSocket.destroy());
  clientSocket.on('error', () => upstreamSocket.destroy());
});

server.listen(listenPort, '0.0.0.0');
