#!/usr/bin/env node
/**
 * SPW 遥控插件 —— 独立测试客户端
 *
 * 不依赖 APP，用来验证「插件是否在监听 / 协议是否通 / 播放指令是否生效」。
 *
 * 用法：
 *   node spw-remote-cli.js <host> [port]           连接指定主机
 *   node spw-remote-cli.js --scan [port]           扫描本机所在网段找插件
 *   node spw-remote-cli.js --url ws://1.2.3.4:52100
 *
 * 连接成功后进入交互模式，可输入：
 *   play | pause | next | prev | seek <毫秒> | state | hello | raw <json> | help | quit
 */
'use strict';

const net = require('net');
const os = require('os');
const readline = require('readline');
const WebSocket = require('ws');

const DEFAULT_PORT = 52100;
const PROBE_TIMEOUT_MS = 400;

// ---------------------------------------------------------------- 参数解析

function parseArgs(argv) {
  const out = { mode: 'connect', host: null, port: DEFAULT_PORT, url: null };
  const rest = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--scan') out.mode = 'scan';
    else if (a === '--url') out.url = argv[++i];
    else if (a === '--help' || a === '-h') out.mode = 'help';
    else rest.push(a);
  }
  if (out.url) {
    try {
      const u = new URL(out.url);
      out.host = u.hostname;
      out.port = Number(u.port || DEFAULT_PORT);
      out.mode = 'connect';
    } catch {
      console.error(`无法解析 --url: ${out.url}`);
      process.exit(2);
    }
  } else {
    if (rest[0]) out.host = rest[0];
    if (rest[1]) out.port = Number(rest[1]);
  }
  return out;
}

// ---------------------------------------------------------------- 网段扫描

/** 收集本机所有 IPv4 的非回环地址 */
function localIPv4() {
  const list = [];
  for (const infos of Object.values(os.networkInterfaces())) {
    for (const info of infos || []) {
      if (info.family === 'IPv4' && !info.internal) list.push(info);
    }
  }
  return list;
}

/** 对单个 host:port 做 TCP 连通性探测 */
function probe(host, port) {
  return new Promise((resolve) => {
    const sock = new net.Socket();
    let done = false;
    const finish = (ok) => {
      if (done) return;
      done = true;
      sock.destroy();
      resolve(ok);
    };
    sock.setTimeout(PROBE_TIMEOUT_MS);
    sock.once('connect', () => finish(true));
    sock.once('timeout', () => finish(false));
    sock.once('error', () => finish(false));
    sock.connect(port, host);
  });
}

/** 并发扫描本机所在 /24 网段 */
async function scan(port) {
  const ifaces = localIPv4();
  if (ifaces.length === 0) {
    console.log('没有找到可用的本机 IPv4 地址');
    return [];
  }

  const targets = new Set();
  for (const info of ifaces) {
    const prefix = info.address.split('.').slice(0, 3).join('.');
    console.log(`扫描网段 ${prefix}.0/24 （网卡 ${info.address}）...`);
    for (let i = 1; i <= 254; i++) targets.add(`${prefix}.${i}`);
  }

  const hosts = [...targets];
  const found = [];
  const CONCURRENCY = 128;
  let idx = 0;

  async function worker() {
    while (idx < hosts.length) {
      const h = hosts[idx++];
      if (h === undefined) break;
      // eslint-disable-next-line no-await-in-loop
      if (await probe(h, port)) found.push(h);
    }
  }

  await Promise.all(Array.from({ length: CONCURRENCY }, worker));
  return found;
}

// ---------------------------------------------------------------- 主程序

const HELP = `可用指令：
  play / pause / next / prev    播放控制
  seek <毫秒>                   跳转到指定位置
  state                         请求当前状态
  hello                         重新握手
  raw <json>                    直接发送原始 JSON
  help                          显示本帮助
  quit                          退出
`;

function fmtState(m) {
  const mm = String(Math.floor((m.pos || 0) / 60000)).padStart(2, '0');
  const ss = String(Math.floor(((m.pos || 0) % 60000) / 1000)).padStart(2, '0');
  const icon = m.playing ? '▶' : '⏸';
  const title = m.title || '(未知曲目)';
  const artist = m.artist ? ` - ${m.artist}` : '';
  return `${icon} ${mm}:${ss}  ${title}${artist}`;
}

function connect(url) {
  console.log(`连接 ${url} ...`);
  const ws = new WebSocket(url);
  let msgId = 0;
  let connected = false;

  ws.on('open', () => {
    connected = true;
    console.log('✅ 已连接，发送 hello');
    ws.send(JSON.stringify({ t: 'hello' }));
  });

  ws.on('message', (data) => {
    let parsed = null;
    const text = data.toString();
    try {
      parsed = JSON.parse(text);
    } catch {
      console.log(`⬅️  (非 JSON) ${text}`);
      return;
    }

    switch (parsed.t) {
      case 'state':
        console.log(`⬅️  state  ${fmtState(parsed)}`);
        break;
      case 'ack':
        console.log(`⬅️  ack    id=${JSON.stringify(parsed.id)} ok=${parsed.ok}` +
          (parsed.err ? ` err=${parsed.err}` : ''));
        break;
      case 'pong':
        console.log(`⬅️  pong   RTT=${Date.now() - (parsed.ts || Date.now())}ms`);
        break;
      default:
        console.log(`⬅️  ${text}`);
    }
  });

  ws.on('close', (code, reason) => {
    console.log(`\n连接已关闭 (code=${code}${reason && reason.length ? `, ${reason}` : ''})`);
    process.exit(connected ? 0 : 1);
  });

  ws.on('error', (err) => {
    console.error(`❌ 连接错误: ${err.message}`);
    if (err.code === 'ECONNREFUSED') {
      console.error('   目标端口没有监听：插件未启用，或端口不对，或防火墙拦截');
    }
    if (err.code === 'ETIMEDOUT' || err.code === 'EHOSTUNREACH') {
      console.error('   网络不可达：检查两台设备是否在同一网段 / Tailscale 是否都已登录');
    }
  });

  const rl = readline.createInterface({ input: process.stdin, output: process.stdout, prompt: 'spw> ' });
  rl.prompt();

  rl.on('line', (line) => {
    const input = line.trim();
    if (!input) return rl.prompt();

    const [cmd, ...args] = input.split(/\s+/);
    const send = (obj) => {
      if (ws.readyState !== WebSocket.OPEN) {
        console.log('连接不可用，无法发送');
        return;
      }
      ws.send(JSON.stringify(obj));
    };

    switch (cmd) {
      case 'quit':
      case 'exit':
        ws.close();
        return;
      case 'help':
        console.log(HELP);
        break;
      case 'raw':
        send(JSON.parse(args.join(' ')));
        break;
      case 'state':
        send({ t: 'hello' });
        break;
      case 'hello':
        send({ t: 'hello' });
        break;
      case 'seek':
        send({ t: 'cmd', id: ++msgId, action: 'seek', pos: Number(args[0]) });
        break;
      case 'play':
      case 'pause':
      case 'next':
      case 'prev':
        send({ t: 'cmd', id: ++msgId, action: cmd });
        break;
      default:
        console.log(`未知指令: ${cmd}（输入 help 查看）`);
    }
    rl.prompt();
  });

  rl.on('close', () => {
    if (ws.readyState === WebSocket.OPEN) ws.close();
  });
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));

  if (opts.mode === 'help') {
    console.log(`SPW 遥控测试客户端

用法：
  node spw-remote-cli.js <host> [port]
  node spw-remote-cli.js --scan [port]

示例：
  node spw-remote-cli.js --scan
  node spw-remote-cli.js 192.168.0.106
  node spw-remote-cli.js 100.101.102.103 52100
`);
    return;
  }

  if (opts.mode === 'scan') {
    console.log(`扫描端口 ${opts.port} 上的 SPW 遥控插件...\n`);
    const found = await scan(opts.port);
    if (found.length === 0) {
      console.log('\n❌ 未发现监听该端口的设备。');
      console.log('排查建议：');
      console.log('  1) 确认插件已在 Salt Player 中「启用」（不是仅安装）');
      console.log('  2) 确认端口与插件配置里的一致');
      console.log('  3) 确认两台设备在同一网段，或 Tailscale 两端都已登录');
      console.log('  4) 检查 Windows 防火墙是否拦截了入站连接');
      return;
    }
    console.log(`\n✅ 发现 ${found.length} 个候选：`);
    for (const h of found) console.log(`   ws://${h}:${opts.port}`);
    console.log(`\n用以下命令连接：node spw-remote-cli.js ${found[0]} ${opts.port}`);
    return;
  }

  if (!opts.host) {
    console.error('需要指定主机。用 --scan 自动发现，或 --help 查看用法。');
    process.exit(2);
  }

  connect(`ws://${opts.host}:${opts.port}`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
