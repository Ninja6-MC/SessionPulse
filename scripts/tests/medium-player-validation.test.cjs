const test = require('node:test');
const assert = require('node:assert/strict');
const runner = require('../medium-player-validation.cjs');
test('import is offline and parses truncated figures', () => {
  assert.deepEqual(runner.parseTime('Your counted window: 0.2h (15 min). Lifetime: 1.7h.'), { minutes: 15, hours: 0.2, lifetimeHours: 1.7 });
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
