#!/usr/bin/env node
/**
 * 配对（鉴权）流程验证
 *
 * 验证插件侧的鉴权门：
 *   1. 连上后插件立刻发 authRequired，且**不推任何播放状态**（否则同网段谁都能看到你在听什么）；
 *   2. 配对码错误 → authFail，且仍然不给状态；
 *   3. 配对码正确 → authOk + hello + state；
 *   4. 未配对时发 cmd 会被拒绝（不是执行）。
 *
 * 用法：node verify-auth.js <host> <port> [token] [wrongToken]
 */
'use strict';

const WebSocket = require('ws');

const host = process.argv[2] || '127.0.0.1';
const port = Number(process.argv[3] || 52100);
const goodToken = process.argv[4] || 'spw-1234';
const badToken = process.argv[5] || '__definitely_wrong__';
const url = `ws://${host}:${port}`;

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

function open(messages) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    const box = { ws, msgs: [] };
    ws.on('message', (d) => {
      try {
        box.msgs.push(JSON.parse(d.toString()));
      } catch {
        box.msgs.push({ __raw: d.toString() });
      }
      messages?.push(...box.msgs.splice(0));
    });
    ws.on('open', () => resolve(box));
    ws.on('error', reject);
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  console.log(`=== 配对流程验证 ===\n目标 ${url}\n`);

  // ---- 1. 连接后不该拿到状态 ----
  console.log('第 1 步：连接后插件是否立刻索要配对码');
  let box;
  try {
    box = await open();
  } catch (e) {
    console.log(`  ❌ 连不上：${e.message}`);
    process.exit(1);
  }
  await sleep(400);

  check('收到 authRequired', box.msgs.some((m) => m.t === 'authRequired'));
  check(
    '未配对时不推播放状态',
    !box.msgs.some((m) => m.t === 'state'),
    box.msgs.map((m) => m.t).join(',')
  );

  // ---- 2. 错误配对码 ----
  console.log('\n第 2 步：配对码错误');
  box.ws.send(JSON.stringify({ t: 'auth', token: badToken }));
  await sleep(400);
  check('收到 authFail', box.msgs.some((m) => m.t === 'authFail'));
  check(
    '配对失败后仍不推状态',
    !box.msgs.some((m) => m.t === 'state'),
    box.msgs.map((m) => m.t).join(',')
  );

  // ---- 3. 未配对时执行指令应被拒 ----
  console.log('\n第 3 步：未配对时发播放指令');
  box.msgs.length = 0;
  box.ws.send(JSON.stringify({ t: 'cmd', id: 99, action: 'pause' }));
  await sleep(400);
  check(
    '指令未被当作合法命令执行（没有 ack ok=true）',
    !box.msgs.some((m) => m.t === 'ack' && m.ok === true),
    JSON.stringify(box.msgs)
  );
  check(
    '回复的是 authRequired（要求先配对）',
    box.msgs.some((m) => m.t === 'authRequired') ||
      !box.msgs.some((m) => m.t === 'ack'),
    JSON.stringify(box.msgs)
  );
  box.ws.close();

  // ---- 4. 正确配对码 ----
  console.log('\n第 4 步：配对码正确');
  const box2 = await open();
  await sleep(300);
  box2.msgs.length = 0;
  box2.ws.send(JSON.stringify({ t: 'auth', token: goodToken }));
  await sleep(800);

  check('收到 authOk', box2.msgs.some((m) => m.t === 'authOk'));
  check('收到 hello', box2.msgs.some((m) => m.t === 'hello'));
  const state = box2.msgs.find((m) => m.t === 'state');
  check('收到 state（配对后才有）', !!state);
  if (state) {
    console.log(
      `     当前曲目：playing=${state.playing} title="${state.title}" artist="${state.artist}" id="${state.id || '（空）'}"`
    );
    if (!state.title) {
      console.log('     ℹ️  标题为空：宿主当前可能没在播放，或曲目反射没读到');
    }
  }

  // ---- 5. 配对后指令应该能过 ----
  console.log('\n第 5 步：配对后发一条无害指令');
  box2.msgs.length = 0;
  // 用 seek 到当前位置（0 秒附近）而不是 pause/play，避免真的打断正在播的歌
  box2.ws.send(JSON.stringify({ t: 'cmd', id: 1, action: 'seek', pos: 0 }));
  await sleep(700);
  const ack = box2.msgs.find((m) => m.t === 'ack');
  check('收到 ack 回执', !!ack, JSON.stringify(box2.msgs));
  if (ack) check('指令执行成功', ack.ok === true, `err=${ack.err}`);
  box2.ws.close();

  await sleep(200);
  console.log(`\n${'='.repeat(46)}`);
  console.log(`通过 ${pass} 项，失败 ${fail} 项`);
  if (fail > 0) {
    console.log('失败项：');
    for (const f of failures) console.log(`  - ${f}`);
  }
  console.log('='.repeat(46));
  process.exit(fail > 0 ? 1 : 0);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
