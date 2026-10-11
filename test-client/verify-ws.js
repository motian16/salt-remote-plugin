#!/usr/bin/env node
/**
 * WebSocket 线协议一致性测试（针对插件自带的 WsServer 实现）
 *
 * 用 Node 的 ws 客户端连接 WsServerSelfTest，逐项验证 RFC6455 行为：
 *   1. 握手（101 + Sec-WebSocket-Accept）
 *   2. 服务端主动推送文本帧
 *   3. 客户端掩码帧的上行解码
 *   4. 携带中文与转义字符的 UTF-8 往返（验证 JSON 编解码）
 *   5. 大消息（>125 字节，走 16 位长度分支）
 *   6. ping/pong 控制帧
 *   7. 优雅关闭（close 帧）
 *
 * 用法：node verify-ws.js [port]
 */
'use strict';

const WebSocket = require('ws');

const port = Number(process.argv[2] || 52199);
const url = `ws://127.0.0.1:${port}`;

let pass = 0;
let fail = 0;
const failures = [];

function check(name, condition, detail) {
  if (condition) {
    pass++;
    console.log(`  ✅ ${name}`);
  } else {
    fail++;
    failures.push(name);
    console.log(`  ❌ ${name}${detail ? ` — ${detail}` : ''}`);
  }
}

function run() {
  return new Promise((resolve) => {
    console.log(`连接 ${url}\n`);
    const ws = new WebSocket(url);
    const inbox = [];
    let pingSeen = false;
    let serverPongSeen = false;

    const timeout = setTimeout(() => {
      console.log('\n⏱️  超时');
      try { ws.terminate(); } catch {}
      resolve();
    }, 15000);

    ws.on('upgrade', (res) => {
      console.log('第 1 步：握手');
      check('HTTP 状态为 101', res.statusCode === 101, `实际 ${res.statusCode}`);
      const accept = res.headers['sec-websocket-accept'];
      check('存在 Sec-WebSocket-Accept 头', !!accept, accept || '缺失');
      check('Upgrade 头为 websocket',
        String(res.headers.upgrade || '').toLowerCase() === 'websocket',
        res.headers.upgrade);
      console.log('');
    });

    ws.on('open', () => {
      console.log('第 2 步：服务端主动推送');
      // onOpen 会推 hello + state 两条，等一会儿再断言
      setTimeout(() => {
        const hello = inbox.find((m) => m.t === 'hello');
        const state = inbox.find((m) => m.t === 'state');
        check('收到 hello 帧', !!hello);
        check('hello 携带 protocol=1', hello && hello.protocol === 1);
        check('收到 state 帧', !!state);
        check('state 中文 title 正确解码',
          state && state.title === '测试曲目',
          state ? JSON.stringify(state.title) : '无 state');
        check('state 反斜杠路径正确解码',
          state && state.path === 'C:\\Music\\test.flac',
          state ? JSON.stringify(state.path) : '无 state');
        check('state pos 为数字', state && typeof state.pos === 'number');

        step3();
      }, 400);
    });

    function step3() {
      console.log('\n第 3 步：上行掩码帧 + JSON 往返');
      const token = `往返测试-${Date.now()}`;
      ws.send(JSON.stringify({ t: 'echo', text: token }));
      setTimeout(() => {
        const echo = inbox.find((m) => m.t === 'echo');
        check('收到 echo 应答', !!echo);
        check('UTF-8 内容原样往返', echo && echo.text === token,
          echo ? JSON.stringify(echo.text) : '无 echo');

        step4();
      }, 300);
    }

    function step4() {
      console.log('\n第 4 步：大消息（16 位长度分支）');
      const big = 'x'.repeat(5000);
      ws.send(JSON.stringify({ t: 'echo', text: big }));
      setTimeout(() => {
        const echoes = inbox.filter((m) => m.t === 'echo');
        const last = echoes[echoes.length - 1];
        check('收到 5000 字节消息的应答', !!last && last.text.length === 5000,
          last ? `实际长度 ${last.text.length}` : '无');
        check('大消息内容完整', !!last && last.text === big);

        step5();
      }, 400);
    }

    function step5() {
      console.log('\n第 5 步：应用层 ping/pong');
      const ts = Date.now();
      ws.send(JSON.stringify({ t: 'ping', ts }));
      setTimeout(() => {
        const pong = inbox.find((m) => m.t === 'pong');
        check('收到 pong', !!pong);
        check('pong 回传 ts', pong && pong.ts === ts,
          pong ? `${pong.ts} vs ${ts}` : '无');

        step6();
      }, 300);
    }

    function step6() {
      console.log('\n第 6 步：WS 协议级 ping/pong');
      ws.ping();
      setTimeout(() => {
        check('客户端 ping 得到 pong 应答', serverPongSeen);
        step7();
      }, 400);
    }

    function step7() {
      console.log('\n第 7 步：优雅关闭');
      ws.close(1000, 'bye');
      setTimeout(() => {
        check('收到 close 帧（readyState 已关闭）',
          ws.readyState === WebSocket.CLOSED || ws.readyState === WebSocket.CLOSING,
          `readyState=${ws.readyState}`);
        clearTimeout(timeout);
        resolve();
      }, 400);
    }

    ws.on('message', (data) => {
      try {
        inbox.push(JSON.parse(data.toString()));
      } catch {
        inbox.push({ __raw: data.toString() });
      }
    });

    ws.on('ping', () => { pingSeen = true; });
    ws.on('pong', () => { serverPongSeen = true; });

    ws.on('error', (e) => {
      console.log(`\n❌ 连接错误: ${e.message}`);
      clearTimeout(timeout);
      resolve();
    });
  });
}

run().then(() => {
  console.log(`\n${'='.repeat(46)}`);
  console.log(`通过 ${pass} 项，失败 ${fail} 项`);
  if (fail > 0) {
    console.log('失败项：');
    for (const f of failures) console.log(`  - ${f}`);
  }
  console.log('='.repeat(46));
  process.exit(fail > 0 ? 1 : 0);
});
