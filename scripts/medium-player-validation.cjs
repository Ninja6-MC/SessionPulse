#!/usr/bin/env node
// Importing this module is read-only; only explicit invocation connects a player.
const fs = require('node:fs');
const path = require('node:path');
const { performance } = require('node:perf_hooks');
const PREFIX = 'Ninja6 \u00bb ';
const EXPECTED = [
  { minute: 5, chat: "\u{1f4a7} You've been playing for 5 minutes. Stay hydrated!", title: '5 Minutes', subtitle: 'Stay hydrated & stretch', actionbar: '\u{1f4a7} 5m Played \u2014 Stay Hydrated!', sound: 'block.note_block.chime' },
  { minute: 10, chat: '\u{1f9d8} 10 minutes in. Check your posture.', title: '10 Minutes', subtitle: 'Check your posture', actionbar: '\u{1f9d8} 10m Played \u2014 Posture Check!', sound: 'block.note_block.chime' },
  { minute: 15, chat: '\u{1f440} 15 minutes in. Look 20 feet away for 20 seconds.', title: '15 Minutes', subtitle: 'Look 20 feet away for 20s', actionbar: '\u{1f440} 15m Played \u2014 Eye Break!', sound: 'block.note_block.bell' }
];
function parseTime(text) {
  if (!text.startsWith(PREFIX)) return null;
  const m = text.slice(PREFIX.length).match(/^Your counted window: (\d+\.\d)h \((\d+) min\)\. Lifetime: (\d+\.\d)h\.$/);
  return m ? { minutes: Number(m[2]), hours: Number(m[1]), lifetimeHours: Number(m[3]) } : null;
}
function varint(value) {
  const bytes = [];
  do { let byte = value & 127; value >>>= 7; if (value) byte |= 128; bytes.push(byte); } while (value);
  return Buffer.from(bytes);
}
function shortString(text) {
  const data = Buffer.from(text, 'utf8'); const size = Buffer.alloc(2); size.writeUInt16BE(data.length);
  return Buffer.concat([size, data]);
}
function loginPacket(password) {
  // 1.21.11 configuration packet 0x08: ID, payload length, nameless compound NBT.
  // minecraft-data omits the payload length; ordinary custom_click_action encoding fails.
  const id = Buffer.from('authme:prejoin-login/submit');
  const nbt = Buffer.concat([Buffer.from([10, 8]), shortString('password'), shortString(password), Buffer.from([0])]);
  return Buffer.concat([varint(8), varint(id.length), id, varint(nbt.length), nbt]);
}
function idleVerdict(before, after, elapsed) {
  // A read is input: each silent 480s span starts a fresh 300s idle timer.
  // Minute truncation permits 4..6 credited minutes; AFK OFF would credit ~8.
  const delta = after.minutes - before.minutes;
  return elapsed >= 475 && delta >= 4 && delta <= 6;
}
function classifyEvent(channel, text) {
  const normalized = text.replace(/<\/?(?:red|gray|yellow|aqua|gold|white|green|blue|bold|italic|reset)>/g, '')
    .replace(/\u00a7[0-9a-fk-orx]/gi, '');
  const reply = channel === 'chat' && (parseTime(normalized)
    || ['You do not have permission to do that.', 'SessionPulse commands', 'Top playtime'].some(reply => normalized === PREFIX + reply));
  const reminder = EXPECTED.some(item => {
    if (channel === 'chat') return normalized === PREFIX + item.chat;
    return ['actionbar', 'title', 'subtitle'].includes(channel) && normalized === item[channel];
  });
  return reply || reminder ? { text: normalized, malformed: normalized !== text } : null;
}
function hasMilestoneReplay(events) {
  return events.some(event => typeof event.text === 'string' && EXPECTED.some(item => {
    if (event.channel === 'chat') return event.text === PREFIX + item.chat;
    return ['title', 'subtitle', 'actionbar'].includes(event.channel) && event.text === item[event.channel];
  }));
}
async function main() {
  const username = process.env.SPULSE_USERNAME, password = process.env.SPULSE_PASSWORD;
  if (!/^[A-Za-z0-9_]{3,16}$/.test(username || '') || !/^[!-~]{8,256}$/.test(password || '')) throw new Error('Provision a disposable account and supply credentials through environment variables.');
  if (!process.env.BOT_MODULE_ROOT) throw new Error('BOT_MODULE_ROOT is required.');
  delete process.env.DEBUG; // Protocol debugging may print authentication payloads.
  const root = process.env.BOT_MODULE_ROOT;
  const mineflayer = require(path.join(root, 'mineflayer'));
  const nbt = require(path.join(root, 'prismarine-nbt'));
  // Install before the first bot compiles its packet parser; no dependency files change.
  require('./nbt-utf8-reader.cjs').installNbtUtf8Reader(nbt, require(path.join(root, 'protodef')).utils.PartialReadError);
  const port = Number(process.env.SPULSE_PORT || 42567);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid port.');
  const output = path.resolve(process.env.SPULSE_OUTPUT || 'sessionpulse-medium-validation.json');
  const result = { startedAt: new Date().toISOString(), target: 'Medium Paper 26.2', client: '1.21.11 through ViaVersion; not native 26.3 evidence', account: username,
    candidate: { version: process.env.SPULSE_CANDIDATE_VERSION || '0.1.0-rc.1-7-g98e7568', sha256: process.env.SPULSE_CANDIDATE_SHA256 || '84d28cfd8d20e16bd37a34235ab3e564aee79f9476f588ee92a2279998b8525c', identity: 'Operator must verify installed bytes; a player connection cannot attest them.' },
    server: { build: process.env.SPULSE_SERVER_BUILD || 'Paper 26.2-129-ver/26.2@9240f58', sha256: process.env.SPULSE_SERVER_SHA256 || 'b1d8f6bfa1b6101fa8e947b53041cb3bdf5540e7b83b6547ca19ba7edefeb083', identity: 'Operator-provided expected identity, not verified by bot.' },
    checks: [], samples: [], events: [], authentication: [], outcome: 'RUNNING',
    manual: ['Native-client visual/color/audio inspection.', 'Restart/reload and EssentialsX integration; none performed by this runner.', 'No enforcement, server configuration, world edits, OP or account provisioning.'] };
  const started = performance.now(); let bot, fatal; let closing = false;
  const clean = value => String(value).split(password).join('[redacted]').split(JSON.stringify(password).slice(1, -1)).join('[redacted]');
  const save = () => fs.writeFileSync(output, JSON.stringify(result, null, 2), { mode: 0o600 });
  const assert = (ok, message) => { if (!ok) throw new Error(message); };
  async function wait(condition, label, seconds = 20) {
    const end = performance.now() + seconds * 1000;
    while (performance.now() < end) {
      if (fatal) throw new Error(fatal);
      if (condition()) return;
      await new Promise(resolve => setTimeout(resolve, 200));
    }
    throw new Error('Timed out: ' + label);
  }
  async function delay(seconds) { const end = performance.now() + seconds * 1000; await wait(() => performance.now() >= end, 'bounded wait', seconds + 2); }
  async function check(name, operation) {
    try { result.checks.push({ name, status: 'PASS', evidence: await operation() }); save(); }
    catch (error) { result.checks.push({ name, status: 'FAIL', evidence: clean(error.message) }); save(); throw error; }
  }
  function event(channel, raw) {
    const accepted = classifyEvent(channel, clean(raw));
    if (!accepted) return;
    // Store only exact allowed text; malformed matching output fails without raw chat.
    result.events.push({ second: Math.round((performance.now() - started) / 1000), channel, text: accepted.text });
    if (accepted.malformed) fatal = 'Unparsed formatting in plugin output.';
    save();
  }
  function close() {
    closing = true; const target = bot;
    if (target) { target.quit(); setTimeout(() => target._client.socket?.destroy(), 2000).unref(); }
  }
  async function connect() {
    closing = false; fatal = null;
    bot = mineflayer.createBot({ host: process.env.SPULSE_HOST || '127.0.0.1', port, username, auth: 'offline', version: '1.21.11', hideErrors: true });
    let spawned = false, dialogSent = false, authenticated = false;
    bot._client.on('show_dialog', data => {
      try {
        if (bot._client.state !== 'configuration') throw new Error('Unexpected in-game authentication dialog.');
        if (!JSON.stringify(nbt.simplify(data.dialog)).includes('authme:prejoin-login/submit')) throw new Error('Unknown prejoin dialog; no credentials submitted.');
        if (!dialogSent) { dialogSent = true; bot._client.writeRaw(loginPacket(password)); result.authentication.push('configuration AuthMe submit'); }
      } catch (error) { fatal = clean(error.message); }
    });
    bot.on('message', (message, position) => {
      const text = message.toString();
      if (/successfully logged|successful login|login successful|authenticated/i.test(text)) authenticated = true;
      if (/please register|\/register <|not registered/i.test(text)) fatal = 'Account is not provisioned; operator registration required.';
      event(position === 'game_info' ? 'actionbar' : 'chat', text);
    });
    const ChatMessage = require(path.join(root, 'prismarine-chat'))(bot.registry);
    for (const [packet, channel] of [['set_title_text', 'title'], ['set_title_subtitle', 'subtitle'], ['action_bar', 'actionbar']]) {
      bot._client.on(packet, data => { try { event(channel, ChatMessage.fromNotch(data.text).toString()); } catch { fatal = 'Cannot decode player text packet.'; } });
    }
    for (const packet of ['sound_effect', 'entity_sound_effect']) bot._client.on(packet, data => {
      // Capture packet occurrence without claiming source attribution or audibility.
      result.events.push({ second: Math.round((performance.now() - started) / 1000), channel: 'sound', packet, soundId: data.soundId ?? null });
    });
    bot.on('error', error => { fatal = 'Client error: ' + clean(error.message); });
    bot.on('kicked', () => { fatal = 'Server kicked bot; investigate authentication/environment.'; });
    bot.on('end', () => { if (!closing) fatal = 'Unexpected disconnect.'; });
    bot.once('spawn', () => { spawned = true; bot.physicsEnabled = false; });
    await wait(() => spawned, 'authenticated spawn', 90); await delay(2);
    if (!dialogSent) { bot.chat('/login ' + password); result.authentication.push('legacy login'); await wait(() => authenticated, 'AuthMe login', 25); }
  }
  async function query(command = '/spulse time') {
    const since = result.events.length; bot.chat(command);
    await wait(() => result.events.slice(since).some(e => e.channel === 'chat' && parseTime(e.text)), 'counted-time reply');
    const sample = parseTime(result.events.slice(since).find(e => e.channel === 'chat' && parseTime(e.text)).text);
    result.samples.push({ second: Math.round((performance.now() - started) / 1000), ...sample }); save(); return sample;
  }
  async function active(seconds) {
    const end = performance.now() + seconds * 1000;
    while (performance.now() < end) { await query(); await delay(Math.min(30, Math.max(0, (end - performance.now()) / 1000))); }
  }
  const watchdog = setTimeout(() => { fatal = 'Hard limit (30 minutes) reached.'; close(); }, 1800000);
  const stop = () => { fatal = 'Interrupted by operator.'; close(); };
  process.once('SIGINT', stop); process.once('SIGTERM', stop);
  try {
    save(); await connect(); let baseline;
    await check('Fresh account and alias response', async () => { baseline = await query('/sessionpulse time'); assert(baseline.minutes <= 1, 'Window is not fresh; use a new normal account without resetting existing records.'); return baseline; });
    await check('Nonadmin denial (read-only other-player query)', async () => {
      const since = result.events.length; bot.chat('/spulse time SP_ReadOnly');
      await wait(() => result.events.slice(since).some(e => e.text === PREFIX + 'You do not have permission to do that.'), 'permission denial');
      return 'No reset/reload command attempted; missing denial aborts the run.';
    });
    await check('Active time increases', async () => { await active(75); const after = await query(); assert(after.minutes > baseline.minutes, 'No active-time increase.'); return after; });
    for (let i = 1; i <= 2; i++) await check('Silent idle span ' + i, async () => {
      const before = await query(), start = performance.now();
      await delay(480); // No commands, chat, movement, clicks or inventory actions.
      const after = await query(), elapsed = (performance.now() - start) / 1000;
      assert(idleVerdict(before, after, elapsed), 'Growth inconsistent with 300s idle cap at minute resolution.');
      return { before, after, elapsedSeconds: Math.round(elapsed), expected: '4..6 credited minutes across each silent 480s span; not zero.' };
    });
    await check('Activity resumes time', async () => { const before = await query(); await active(75); const after = await query(); assert(after.minutes > before.minutes, 'No resumed-time increase.'); return { before, after }; });
    await check('Configured unaccelerated 5/10/15 text packets', async () => {
      while ((await query()).minutes < 16) await active(40);
      return EXPECTED.map(item => {
        const counts = {};
        for (const channel of ['chat', 'actionbar', 'title', 'subtitle']) {
          const text = channel === 'chat' ? PREFIX + item.chat : item[channel];
          counts[channel] = result.events.filter(e => e.channel === channel && e.text === text).length;
          assert(counts[channel] === 1, `Minute ${item.minute} ${channel}: expected one exact packet, got ${counts[channel]}.`);
        }
        return { minute: item.minute, counts, sound: 'MANUAL: received sound packets do not prove plugin attribution or audibility.' };
      });
    });
    await check('Reconnect retains time without replay', async () => {
      const before = await query(), since = result.events.length;
      close(); await delay(65); await connect(); const after = await query(); await delay(12);
      assert(after.minutes >= before.minutes && after.minutes <= before.minutes + 1, 'Window changed across reconnect.');
      assert(!hasMilestoneReplay(result.events.slice(since)), 'Earlier milestone replayed.');
      return { before, after, secondsObserved: 12, limitation: 'Join/leave only; no server restart.' };
    });
    result.outcome = 'PASS_WITH_MANUAL_GAPS';
  } catch (error) { result.outcome = 'FAIL'; result.error = clean(error.message); process.exitCode = 1; }
  finally { close(); clearTimeout(watchdog); process.removeListener('SIGINT', stop); process.removeListener('SIGTERM', stop); result.finishedAt = new Date().toISOString(); save(); console.log('Evidence: ' + output + ' (' + result.outcome + ')'); }
}
module.exports = { parseTime, loginPacket, idleVerdict, classifyEvent, hasMilestoneReplay, EXPECTED, PREFIX };
if (require.main === module) main().catch(() => { console.error('Cannot start validation: check credentials, port, output path and dependency setup.'); process.exitCode = 1; });
