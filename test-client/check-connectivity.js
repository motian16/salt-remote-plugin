#!/usr/bin/env node
/**
 * 零依赖连通性自检（只用 Node 内置模块，不需要 npm install）
 *
 * 作用：在装任何东西之前，先回答一个问题——
 *   「从这台设备，到底能不能摸到插件的端口？」
 *
 * 用法：
 *   node check-connectivity.js <host> [port]
 *   node check-connectivity.js --scan [port]
 *
 * 它会依次报告：
 *   1. TCP 能否建连（区分 拒绝 / 超时 / 不可达）
 *   2. HTTP Upgrade 握手是否返回 101 且 Sec-WebSocket-Accept 正确
 *   3. 双向往返一个 WebSocket 文本帧是否成功
 */
'use strict';

const net = require('net');
const os = require('os');
const crypto = require('crypto');

const DEFAULT_PORT = 52100;
const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

const ok = (s) => console.log(`  ✅ ${s}`);
const bad = (s) => console.log(`  ❌ ${s}`);
const info = (s) => console.log(`     ${s}`);

function localIPv4() {
  const list = [];
  for (const infos of Object.values(os.networkInterfaces())) {
    for (const info of infos || []) {
      if (info.family === 'IPv4' && !info.internal) list.push(info);
    }
  }
  return list;
}

function probe(host, port, timeout = 500) {
  return new Promise((resolve) => {
    const sock = new net.Socket();
    let done = false;
    const finish = (result) => {
      if (done) return;
      done = true;
      sock.destroy();
      resolve(result);
    };
    sock.setTimeout(timeout);
    sock.once('connect', () => finish('open'));
    sock.once('timeout', () => finish('timeout'));
    sock.once('error', (e) => finish(e.code || 'error'));
    sock.connect(port, host);
  });
}

async function scan(port) {
  const ifaces = localIPv4();
  const targets = new Set();
  for (const i of ifaces) {
    const prefix = i.address.split('.').slice(0, 3).join('.');
    console.log(`扫描 ${prefix}.0/24 （本机 ${i.address}）`);
    for (let n = 1; n <= 254; n++) targets.add(`${prefix}.${n}`);
  }
  const hosts = [...targets];
  const found = [];
  let idx = 0;
  const workers = Array.from({ length: 128 }, async () => {
    while (idx < hosts.length) {
      const h = hosts[idx++];
      if (h === undefined) break;
      // eslint-disable-next-line no-await-in-loop
      if ((await probe(h, port)) === 'open') found.push(h);
    }
  });
  await Promise.all(workers);
  return found;
}

/** 完整走一遍握手 + 一帧收发 */
function fullCheck(host, port) {
  return new Promise((resolve) => {
    const sock = new net.Socket();
    const key = crypto.randomBytes(16).toString('base64');
    let stage = 'connect';
    let buffer = Buffer.alloc(0);
    let done = false;

    const finish = (success, note) => {
      if (done) return;
      done = true;
      sock.destroy();
      resolve({ success, note });
    };

    const timer = setTimeout(() => {
      finish(false, `阶段「${stage}」超时`);
    }, 5000);

    sock.once('error', (e) => {
      clearTimeout(timer);
      finish(false, `阶段「${stage}」错误: ${e.message}`);
    });

    sock.once('connect', () => {
      stage = 'handshake';
      sock.write(
        `GET / HTTP/1.1\r\n` +
        `Host: ${host}:${port}\r\n` +
        `Upgrade: websocket\r\n` +
        `Connection: Upgrade\r\n` +
        `Sec-WebSocket-Key: ${key}\r\n` +
        `Sec-WebSocket-Version: 13\r\n` +
        `\r\n`
      );
    });

    sock.on('data', (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);

      if (stage === 'handshake') {
        const end = buffer.indexOf('\r\n\r\n');
        if (end < 0) return;
        const head = buffer.slice(0, end).toString('utf8');
        buffer = buffer.slice(end + 4);

        const statusLine = head.split('\r\n')[0];
        if (!/101/.test(statusLine)) {
          clearTimeout(timer);
          finish(false, `握手未返回 101，实际: ${statusLine}`);
          return;
        }

        const m = /sec-websocket-accept:\s*(\S+)/i.exec(head);
        const expected = crypto
          .createHash('sha1')
          .update(key + WS_GUID)
          .digest('base64');
        if (!m || m[1] !== expected) {
          clearTimeout(timer);
          finish(false, `Sec-WebSocket-Accept 校验失败（期望 ${expected}，实际 ${m ? m[1] : '缺失'}）`);
          return;
        }

        ok('握手成功，Sec-WebSocket-Accept 校验通过');
        stage = 'frame';
        // 发一个带掩码的 hello 文本帧
        const payload = Buffer.from(JSON.stringify({ t: 'hello' }), 'utf8');
        const mask = crypto.randomBytes(4);
        const masked = Buffer.from(payload);
        for (let i = 0; i < masked.length; i++) masked[i] ^= mask[i & 3];

        const header = payload.length < 126
          ? Buffer.from([0x81, 0x80 | payload.length])
          : Buffer.concat([Buffer.from([0x81, 0x80 | 126]), Buffer.from([payload.length >> 8, payload.length & 0xff])]);

        sock.write(Buffer.concat([header, mask, masked]));
        return;
      }

      if (stage === 'frame') {
        // 解析服务端发来的第一帧（服务端帧不带掩码）
        if (buffer.length < 2) return;
        const len0 = buffer[1] & 0x7f;
        let offset = 2;
        let len = len0;
        if (len0 === 126) {
          if (buffer.length < 4) return;
          len = buffer.readUInt16BE(2);
          offset = 4;
        } else if (len0 === 127) {
          if (buffer.length < 10) return;
          len = Number(buffer.readBigUInt64BE(2));
          offset = 10;
        }
        if (buffer.length < offset + len) return;

        const opcode = buffer[0] & 0x0f;
        if (opcode !== 0x1) {
          clearTimeout(timer);
          finish(false, `收到非文本帧 opcode=0x${opcode.toString(16)}`);
          return;
        }
        const text = buffer.slice(offset, offset + len).toString('utf8');
        clearTimeout(timer);
        ok('成功收到服务端文本帧');
        info(`内容: ${text}`);
        try {
          const parsed = JSON.parse(text);
          if (parsed.t === 'hello') {
            ok(`协议版本 ${parsed.protocol}，插件版本 ${parsed.version}`);
          } else if (parsed.t === 'state') {
            ok('收到播放状态推送（说明扩展点已挂上）');
          }
        } catch {
          info('（内容不是 JSON，但链路是通的）');
        }
        finish(true, '全部通过');
      }
    });
  });
}

async function main() {
  const args = process.argv.slice(2);
  let mode = 'connect';
  let host = null;
  let port = DEFAULT_PORT;

  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--scan') mode = 'scan';
    else if (!host) host = args[i];
    else port = Number(args[i]);
  }

  console.log('=== SPW 遥控连通性自检 ===\n');

  if (mode === 'scan') {
    console.log(`扫描端口 ${port} ...\n`);
    const found = await scan(port);
    if (!found.length) {
      console.log('❌ 未发现监听该端口的设备');
      return;
    }
    console.log(`\n✅ 发现: ${found.join(', ')}`);
    host = found[0];
    console.log(`\n用 ${host} 继续做完整自检：\n`);
  }

  if (!host) {
    console.log('用法: node check-connectivity.js <host> [port]');
    console.log('      node check-connectivity.js --scan [port]');
    return;
  }

  console.log(`目标: ${host}:${port}\n`);
  console.log('第 1 步：TCP 连通性');
  const r = await probe(host, port, 3000);

  if (r === 'open') {
    ok('TCP 端口可达');
  } else if (r === 'ECONNREFUSED') {
    bad('连接被拒绝（ECONNREFUSED）');
    info('端口上没有监听。请确认：插件已「启用」（不是仅安装）/ 端口配置一致 / 宿主正在运行');
    return;
  } else if (r === 'timeout') {
    bad('连接超时');
    info('被防火墙丢弃，或不在同一网段。Windows 防火墙常拦截入站连接');
    return;
  } else if (r === 'EHOSTUNREACH' || r === 'ENETUNREACH') {
    bad('主机不可达');
    info('路由问题：检查是否同一局域网，或 Tailscale 是否两端都已登录');
    return;
  } else {
    bad(`连接失败: ${r}`);
    return;
  }

  console.log('\n第 2 步：WebSocket 握手');
  const result = await fullCheck(host, port);
  if (!result.success) {
    bad(result.note);
    return;
  }

  console.log('\n🎉 结论：这台设备可以正常连接该插件。');
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
