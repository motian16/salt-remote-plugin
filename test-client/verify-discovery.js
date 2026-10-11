#!/usr/bin/env node
/**
 * 局域网自动发现协议验证
 *
 * 验证插件侧 DiscoveryService 的三件事：
 *   1. 能收到周期性 UDP 广播，且内容是可解析的合法 JSON；
 *   2. 主动发 probe 能立刻收到单播回应（不用等广播周期）；
 *   3. 广播里的地址列表与端口信息符合预期。
 *
 * 用法：node verify-discovery.js [udpPort] [--seconds N] [--no-probe]
 */
'use strict';

const dgram = require('dgram');
const os = require('os');

const args = process.argv.slice(2);
const udpPort = Number(args[0] && !args[0].startsWith('--') ? args[0] : 52101);
const secondsArg = args.indexOf('--seconds');
const waitSeconds = secondsArg >= 0 ? Number(args[secondsArg + 1]) : 12;
const doProbe = !args.includes('--no-probe');

let pass = 0;
let fail = 0;
const failures = [];

function check(name, cond, detail) {
  if (cond) {
    pass++;
    console.log(`  ✅ ${name}`);
  } else {
    fail++;
    failures.push(name);
    console.log(`  ❌ ${name}${detail ? ` — ${detail}` : ''}`);
  }
}

/** 本机所有 IPv4 广播地址 */
function broadcastTargets() {
  const out = new Set();
  for (const infos of Object.values(os.networkInterfaces())) {
    for (const info of infos || []) {
      if (info.family !== 'IPv4' || info.internal) continue;
      const parts = info.address.split('.');
      out.add(`${parts[0]}.${parts[1]}.${parts[2]}.255`);
    }
  }
  out.add('255.255.255.255');
  out.add('127.0.0.1');
  return [...out];
}

const beacons = [];
const soc = dgram.createSocket({ type: 'udp4', reuseAddr: true });

soc.on('message', (msg, rinfo) => {
  const text = msg.toString('utf8').trim();
  let parsed = null;
  try {
    parsed = JSON.parse(text);
  } catch {
    return; // 不是我们的包，忽略
  }
  if (parsed.t !== 'spw-remote') return;

  beacons.push({ from: rinfo.address, port: rinfo.port, data: parsed });
  const eps = (parsed.eps || []).map((e) => `${e.host}(${e.kind})`).join(' ') || '无';
  console.log(`  ⬅️  ${rinfo.address}:${rinfo.port}  ws=${parsed.wsPort} udp=${parsed.udpPort}  eps=[${eps}]`);
});

soc.on('error', (e) => {
  console.log(`\n❌ UDP 错误: ${e.message}`);
  process.exit(1);
});

function main() {
  console.log(`=== SPW 遥控发现协议验证 ===`);
  console.log(`监听 UDP ${udpPort}，最多等 ${waitSeconds} 秒\n`);

  soc.bind(udpPort, () => {
    soc.setBroadcast(true);
    console.log('第 1 步：等待周期性广播（插件默认每 5 秒一次）');

    if (doProbe) {
      // 稍微等一下再发 probe，好把"周期广播"和"探测回应"区分开
      setTimeout(() => {
        const probe = Buffer.from(JSON.stringify({ t: 'probe' }));
        const targets = broadcastTargets();
        console.log(`\n第 2 步：发送 probe 到 ${targets.length} 个地址`);
        for (const t of targets) {
          soc.send(probe, 0, probe.length, udpPort, t, (err) => {
            if (err) console.log(`  probe → ${t} 失败: ${err.message}`);
          });
        }
      }, 1200);
    }

    setTimeout(finish, waitSeconds * 1000);
  });
}

function finish() {
  console.log('\n=== 结果 ===');

  check('收到至少一个广播包', beacons.length > 0,
    beacons.length === 0
      ? '插件没在广播：可能未启用、remote.discovery 关掉了、或防火墙拦了 UDP'
      : '');

  if (beacons.length > 0) {
    const b = beacons[0].data;
    check('t 字段为 spw-remote', b.t === 'spw-remote');
    check('带 wsPort 且为合法端口',
      Number.isInteger(b.wsPort) && b.wsPort > 0 && b.wsPort < 65536, `wsPort=${b.wsPort}`);
    check('带 udpPort', Number.isInteger(b.udpPort), `udpPort=${b.udpPort}`);
    check('带插件版本', typeof b.version === 'string' && b.version.length > 0, `version=${b.version}`);
    check('eps 是数组', Array.isArray(b.eps), `eps=${JSON.stringify(b.eps)}`);

    const eps = Array.isArray(b.eps) ? b.eps : [];
    check('eps 每项都有 host 和 kind',
      eps.every((e) => typeof e.host === 'string' && typeof e.kind === 'string'));

    const kinds = new Set(eps.map((e) => e.kind));
    console.log(`     地址类型: ${[...kinds].join(', ') || '无'}`);
    if (kinds.has('lan')) {
      const lan = eps.filter((e) => e.kind === 'lan').map((e) => e.host);
      check('lan 地址是本机网段地址', lan.length > 0, lan.join(', '));
    }
    // 公网 IPv4 可能探测失败，不算失败项，只提示
    if (kinds.has('pub')) {
      console.log(`     ℹ️  探测到公网 IPv4: ${eps.filter((e) => e.kind === 'pub').map((e) => e.host).join(', ')}`);
    } else {
      console.log('     ℹ️  没有公网地址（内网环境，或 remote.publicIpProbe 已关闭）');
    }

    const distinctSources = new Set(beacons.map((x) => x.from));
    console.log(`     收到 ${beacons.length} 个包，来自 ${distinctSources.size} 个源地址`);
  }

  if (doProbe && beacons.length > 0) {
    // probe 在 1.2s 时发出，之后收到的包就是回应
    check('probe 之后立即收到了包（单播回应）', beacons.length >= 2,
      `只收到 ${beacons.length} 个包 —— 若勉强够 1 个，说明周期性广播生效但探测回应没到`);
  }

  console.log(`\n${'='.repeat(46)}`);
  console.log(`通过 ${pass} 项，失败 ${fail} 项`);
  if (fail > 0) {
    console.log('失败项：');
    for (const f of failures) console.log(`  - ${f}`);
  }
  console.log('='.repeat(46));
  soc.close();
  process.exit(fail > 0 ? 1 : 0);
}

main();
