// NBT uses Java Modified UTF-8; protocol strings outside NBT keep their own codec.
function decodeNbtUtf8(bytes) {
  const units = [];
  function next() {
    if (index >= bytes.length || (bytes[index] & 0xc0) !== 0x80) throw new Error('Invalid NBT UTF-8 continuation.');
    return bytes[index++] & 0x3f;
  }
  let index = 0;
  while (index < bytes.length) {
    const first = bytes[index++]; let value;
    if (first < 0x80) value = first;
    else if ((first & 0xe0) === 0xc0) {
      value = ((first & 0x1f) << 6) | next();
      if (value < 0x80 && !(first === 0xc0 && value === 0)) throw new Error('Overlong NBT UTF-8.');
    } else if ((first & 0xf0) === 0xe0) {
      value = ((first & 0x0f) << 12) | (next() << 6) | next();
      if (value < 0x800) throw new Error('Overlong NBT UTF-8.');
    } else if ((first & 0xf8) === 0xf0) {
      // Some intermediaries send standard UTF-8 scalar encoding instead.
      value = ((first & 7) << 18) | (next() << 12) | (next() << 6) | next();
      if (value < 0x10000 || value > 0x10ffff) throw new Error('Invalid NBT UTF-8 scalar.');
      units.push(0xd800 + ((value - 0x10000) >> 10), 0xdc00 + ((value - 0x10000) & 0x3ff));
      continue;
    } else throw new Error('Invalid NBT UTF-8 leading byte.');
    units.push(value);
  }
  for (let i = 0; i < units.length; i++) {
    if (units[i] >= 0xd800 && units[i] <= 0xdbff) {
      if (++i >= units.length || units[i] < 0xdc00 || units[i] > 0xdfff) throw new Error('Unpaired NBT UTF-8 surrogate.');
    } else if (units[i] >= 0xdc00 && units[i] <= 0xdfff) throw new Error('Unpaired NBT UTF-8 surrogate.');
  }
  return units.map(unit => String.fromCharCode(unit)).join('');
}
const installed = new WeakSet();
function installNbtUtf8Reader(nbt, PartialReadError) {
  if (installed.has(nbt)) return;
  const original = nbt.addTypesToCompiler;
  nbt.addTypesToCompiler = function(type, compiler) {
    original.call(this, type, compiler);
    if (type !== 'big') return;
    compiler.addTypes({ Read: { shortString: ['native', (buffer, offset) => {
      if (offset + 2 > buffer.length) throw new PartialReadError();
      const length = buffer.readUInt16BE(offset), end = offset + 2 + length;
      if (end > buffer.length) throw new PartialReadError();
      return { value: decodeNbtUtf8(buffer.subarray(offset + 2, end)), size: length + 2 };
    }] }, Write: {}, SizeOf: {} });
  };
  installed.add(nbt);
}
module.exports = { decodeNbtUtf8, installNbtUtf8Reader };
