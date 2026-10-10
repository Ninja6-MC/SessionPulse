const test = require('node:test');
const assert = require('node:assert/strict');
const { boundedGrowth } = require('../essentials-acceptance.cjs');
test('accrual bounds reject stopped or duplicate ticking without assuming exact scheduler timing', () => {
  assert.equal(boundedGrowth(70, 90, 20), true);
  assert.equal(boundedGrowth(70, 87, 20), true);
  assert.equal(boundedGrowth(70, 70, 20), false);
  assert.equal(boundedGrowth(70, 110, 20), false);
  assert.equal(boundedGrowth(70, 60, 20), false);
});

test('fixture message filter keeps only exact scoped reminders and command responses', () => {
  const { fixtureText } = require('../essentials-acceptance.cjs');
  for (const text of ['SPXA:ONE', 'SPXB:TWO', 'SPXA:Your counted window: 0.1h (6 min). Lifetime: 0.1h.', 'SPXA:You do not have permission to do that.']) assert.equal(fixtureText(text), true);
  for (const text of ['Public player: SPXA:ONE', 'SPXA:Someone else counted window: 0.1h (6 min).', 'SPXA:You do not have permission to do that. extra', 'SPXC:ONE']) assert.equal(fixtureText(text), false);
});

test('Essentials unsupported warning survives actual console ANSI formatting', () => {
  const { essentialsWarning } = require('../essentials-acceptance.cjs');
  assert.equal(essentialsWarning('[ERROR]: [Essentials] You are running an unsupported server version!'), true);
  assert.equal(essentialsWarning('\u001b[31;1m[03:30:43 ERROR]: [Essentials] \u001b[91mYou are running an unsupported server version!\u001b[0m'), true);
  assert.equal(essentialsWarning('[OtherPlugin] You are running an unsupported server version!'), false);
  assert.equal(essentialsWarning('[Essentials] Version supported.'), false);
});
