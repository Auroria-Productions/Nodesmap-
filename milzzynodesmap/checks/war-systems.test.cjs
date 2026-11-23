const assert = require('node:assert/strict');
require('../web/war-systems.js');
const adapter = globalThis.NodesWarSystems;

const live = { war: { enabled: true }, attacks: [{ town: 'Alpha' }], warzones: [{ enabled: true }], raids: [{ phase: 'PREP' }] };
let result = adapter.resolve(live, {
  config: { enabled: true },
  systems: {
    flagWar: { enabled: false }, nodesWar: { enabled: true },
    keyPointWars: { enabled: true }, warzones: { enabled: false }, raids: { enabled: false },
  },
  nodesWarBattles: [{ attacker: 'A', defender: 'B', phase: 'LIVE' }],
  keyPointBattles: [{ name: 'Castle', status: 'LIVE', eliminated: ['Joe'] }],
});
assert.equal(result.flagWar.enabled, false);
assert.equal(result.warzones.enabled, false);
assert.equal(result.raids.enabled, false);
assert.equal(result.nodesWar.battles.length, 1);
assert.equal(result.keyPointWars.battles.length, 1);
assert.deepEqual(adapter.counts(result), { enabled: 2, active: 2, enabledIds: ['nodesWar', 'keyPointWars'], activeIds: ['nodesWar', 'keyPointWars'] });
result = adapter.resolve(live, { config: { enabled: false }, systems: { flagWar: { enabled: true } } });
assert.equal(adapter.counts(result).enabled, 0);
result = adapter.resolve(live, null);
assert.equal(result.flagWar.enabled, true); // legacy compatibility
assert.equal(result.warzones.enabled, true);
assert.equal(result.raids.enabled, true);
result = adapter.resolve({}, { systems: { keyPointWars: { enabled: true } }, keyPointBattles: { battles: [{ name: 'Tower', status: 'FINISHED' }] } });
assert.equal(result.keyPointWars.active, false);
result = adapter.resolve({}, { systems: { keyPointWars: { enabled: true } },
  keyPointBattles: { activeBattles: { keep42: { name: 'Mountain keep', phase: 'PREP' } } } });
assert.equal(result.keyPointWars.battles[0].id, 'keep42');
result = adapter.resolve({}, { systems: { keyPointWars: { enabled: true } },
  keyPointBattles: [{ name: 'Castle', phase: 'PENDING' }] });
assert.equal(result.keyPointWars.active, false);
console.log('PASS: adapter legacy, configured visibility, status and keyed state tests');
