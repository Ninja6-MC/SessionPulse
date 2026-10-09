const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');
const { execFileSync } = require('node:child_process');
const { decodeNbtUtf8, installNbtUtf8Reader } = require('../nbt-utf8-reader.cjs');
const runner = require('../medium-player-validation.cjs');
test('Java DataOutputStream.writeUTF fixture and standard UTF-8 preserve emoji', () => {
  // Java writeUTF("\uD83D\uDCA7") produces u16 length 0006 + eda0bdedb2a7.
  assert.equal(decodeNbtUtf8(Buffer.from('eda0bdedb2a7', 'hex')), '\u{1f4a7}');
  assert.equal(decodeNbtUtf8(Buffer.from('\u{1f4a7}', 'utf8')), '\u{1f4a7}');
  assert.equal(decodeNbtUtf8(Buffer.from('41c08042', 'hex')), 'A\0B');
  assert.equal(decodeNbtUtf8(Buffer.from('ASCII \u00bb \u2014', 'utf8')), 'ASCII \u00bb \u2014');
});
test('Java-generated Modified UTF-8 matches fixture bytes', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'spulse-nbt-'));
  const source = path.join(directory, 'NbtUtfFixture.java');
  try {
    fs.writeFileSync(source, 'import java.io.*; import java.util.HexFormat; class NbtUtfFixture { public static void main(String[] args) throws Exception { var b = new ByteArrayOutputStream(); var d = new DataOutputStream(b); d.writeUTF(new String(Character.toChars(0x1f4a7))); System.out.print(HexFormat.of().formatHex(b.toByteArray())); }}');
    const generated = execFileSync('java', ['--source', '17', source], { encoding: 'utf8', timeout: 15000 }).trim();
    assert.equal(generated, '0006eda0bdedb2a7');
    assert.equal(decodeNbtUtf8(Buffer.from(generated.slice(4), 'hex')), '\u{1f4a7}');
  } finally { fs.unlinkSync(source); fs.rmdirSync(directory); }
});
test('malformed encoding fails without replacement or guessed emoji', () => {
  for (const hex of ['80', 'c0', 'c081', 'e08080', 'eda0bd', 'edb2a7', 'eda0bd41', 'f0808080', 'f4908080', 'ff', 'e280']) {
    assert.throws(() => decodeNbtUtf8(Buffer.from(hex, 'hex')), /NBT UTF-8/);
  }
  assert.equal(decodeNbtUtf8(Buffer.from('\ufffd', 'utf8')), '\ufffd');
});
test('compiled full system_chat parser reads Modified UTF-8 and keeps strict assertions', () => {
  assert.ok(process.env.BOT_MODULE_ROOT, 'Set BOT_MODULE_ROOT to existing bot dependencies for offline protocol test.');
  const root = process.env.BOT_MODULE_ROOT;
  const nbt = require(path.join(root, 'prismarine-nbt'));
  const { PartialReadError } = require(path.join(root, 'protodef')).utils;
  // Baseline dependency defect: its already-created standalone parser uses ordinary UTF-8.
  assert.equal(nbt.parseUncompressed(Buffer.from('0800000006eda0bdedb2a7', 'hex')).value, '\ufffd'.repeat(6));
  installNbtUtf8Reader(nbt, PartialReadError);
  installNbtUtf8Reader(nbt, PartialReadError); // Idempotent, without replacing dependency files.
  const { createSerializer, createDeserializer } = require(path.join(root, 'minecraft-protocol/src/transforms/serializer'));
  const serializer = createSerializer({ state: 'play', isServer: true, version: '1.21.11' });
  const parser = createDeserializer({ state: 'play', isServer: false, version: '1.21.11', noErrorLogging: true });
  const ChatMessage = require(path.join(root, 'prismarine-chat'))('1.21.11');
  const text = runner.PREFIX + runner.EXPECTED[0].chat;
  const standard = serializer.createPacketBuffer({ name: 'system_chat', params: { content: { type: 'string', value: text }, isActionBar: false } });
  const emoji = Buffer.from('f09f92a7', 'hex'), at = standard.indexOf(emoji);
  assert.ok(at > 2);
  const modified = Buffer.concat([standard.subarray(0, at), Buffer.from('eda0bdedb2a7', 'hex'), standard.subarray(at + 4)]);
  // NBT anonymous string begins immediately after packet-id varint then tag byte.
  let idLength = 1; while (standard[idLength - 1] & 128) idLength++;
  assert.equal(standard[idLength], 8);
  modified.writeUInt16BE(standard.readUInt16BE(idLength + 1) + 2, idLength + 1);
  for (const buffer of [standard, modified]) {
    const parsed = parser.parsePacketBuffer(buffer);
    assert.equal(parsed.metadata.size, buffer.length);
    const observed = ChatMessage.fromNotch(parsed.data.params.content).toString();
    assert.equal(observed, text);
    assert.deepEqual(runner.classifyEvent('chat', observed), { text, malformed: false });
  }
  assert.throws(() => parser.parsePacketBuffer(modified.subarray(0, modified.length - 2)), error => error.partialReadError === true);
  const corrupted = text.replace('\u{1f4a7}', '\ufffd'.repeat(6));
  assert.equal(runner.classifyEvent('chat', corrupted), null);
  assert.equal(runner.classifyEvent('chat', 'Public player: ' + text), null);
});
