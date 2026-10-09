const test = require('node:test');
const assert = require('node:assert/strict');
const runner = require('../medium-player-validation.cjs');
test('import is offline and parses truncated figures', () => {
  assert.deepEqual(runner.parseTime(runner.PREFIX + 'Your counted window: 0.2h (15 min). Lifetime: 1.7h.'), { minutes: 15, hours: 0.2, lifetimeHours: 1.7 });
  assert.equal(runner.parseTime('Other plugin: 15 min'), null);
});
test('silent spans credit threshold, not zero or full wall time', () => {
  assert.equal(runner.idleVerdict({ minutes: 1 }, { minutes: 6 }, 480), true);
  assert.equal(runner.idleVerdict({ minutes: 1 }, { minutes: 9 }, 480), false);
  assert.equal(runner.idleVerdict({ minutes: 1 }, { minutes: 1 }, 480), false);
  assert.equal(runner.idleVerdict({ minutes: 1 }, { minutes: 6 }, 300), false);
});
test('prejoin packet includes length-prefixed nameless NBT password', () => {
  const packet = runner.loginPacket('fixture-secret');
  assert.equal(packet[0], 8);
  const idLength = packet[1];
  assert.equal(packet.subarray(2, 2 + idLength).toString(), 'authme:prejoin-login/submit');
  const payloadLength = packet[2 + idLength];
  const payload = packet.subarray(3 + idLength);
  assert.equal(payload.length, payloadLength);
  assert.equal(payload[0], 10); assert.equal(payload[1], 8);
  assert.equal(payload.subarray(4, 12).toString(), 'password');
  assert.equal(payload.subarray(14, -1).toString(), 'fixture-secret');
  assert.equal(payload.at(-1), 0);
});

test('long password uses multi-byte payload length, not truncated size', () => {
  const packet = runner.loginPacket('x'.repeat(256));
  const offset = 2 + packet[1];
  assert.equal(packet[offset], 0x8f); assert.equal(packet[offset + 1], 0x02);
  assert.equal(packet.length - offset - 2, 271);
});

test('exact per-channel allowlist excludes unrelated public chat', () => {
  assert.equal(runner.classifyEvent('chat', '<CommunityPlayer> I have 5 Minutes before dinner'), null);
  assert.equal(runner.classifyEvent('chat', '5 Minutes'), null);
  assert.equal(runner.classifyEvent('title', 'Someone said 5 Minutes'), null);
  assert.deepEqual(runner.classifyEvent('title', '5 Minutes'), { text: '5 Minutes', malformed: false });
  const expected = 'Ninja6 \u00bb ' + runner.EXPECTED[0].chat;
  assert.deepEqual(runner.classifyEvent('chat', expected), { text: expected, malformed: false });
  assert.deepEqual(runner.classifyEvent('chat', '<aqua>' + expected + '</aqua>'), { text: expected, malformed: true });
  assert.equal(runner.classifyEvent('chat', 'A player says: ' + expected), null);
});

test('actual Medium command prefix is required for time and denial evidence', () => {
  const body = 'Your counted window: 0.0h (0 min). Lifetime: 0.0h.';
  const time = runner.PREFIX + body;
  const denial = runner.PREFIX + 'You do not have permission to do that.';
  assert.deepEqual(runner.parseTime(time), { minutes: 0, hours: 0, lifetimeHours: 0 });
  assert.equal(runner.parseTime(body), null);
  assert.deepEqual(runner.classifyEvent('chat', time), { text: time, malformed: false });
  assert.deepEqual(runner.classifyEvent('chat', denial), { text: denial, malformed: false });
  assert.equal(runner.classifyEvent('chat', 'You do not have permission to do that.'), null);
  assert.equal(runner.classifyEvent('chat', '<CommunityPlayer> ' + time), null);
  assert.equal(runner.classifyEvent('chat', 'OtherPlugin: ' + denial), null);
  assert.deepEqual(runner.classifyEvent('chat', '<gray>' + time + '</gray>'), { text: time, malformed: true });
});

test('reconnect replay ignores sound events and checks exact text channels', () => {
  assert.equal(runner.hasMilestoneReplay([{ channel: 'sound', packet: 'sound_effect' }]), false);
  assert.equal(runner.hasMilestoneReplay([{ channel: 'sound', text: runner.EXPECTED[0].title }]), false);
  assert.equal(runner.hasMilestoneReplay([{ channel: 'chat', text: runner.PREFIX + 'Your counted window: 0.2h (16 min). Lifetime: 0.2h.' }]), false);
  for (const item of runner.EXPECTED) {
    for (const channel of ['chat', 'title', 'subtitle', 'actionbar']) {
      const text = channel === 'chat' ? runner.PREFIX + item.chat : item[channel];
      assert.equal(runner.hasMilestoneReplay([{ channel, text }]), true);
      assert.equal(runner.hasMilestoneReplay([{ channel, text: 'Unrelated ' + text }]), false);
    }
  }
});
