const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const S = require('../web/square-map.js');
const chunkarrays = [ [0,0,1,0,2,0, 1,1], [-3,2], [4,-9] ];
const bounds = S.bounds(chunkarrays, 8);
assert(bounds && bounds.maxX-bounds.minX === bounds.maxZ-bounds.minZ,'frame must be a square');
for (const a of chunkarrays) for (let i=0;i<a.length;i+=2) {
 assert(a[i]*16 >= bounds.minX && (a[i]+1)*16 <= bounds.maxX);
 assert(a[i+1]*16 >= bounds.minZ && (a[i+1]+1)*16 <= bounds.maxZ);
}
assert.equal(S.bounds([]),null);
for (const [a,b] of [[[1,2],[5,4]],[[-4,-2],[-1,-8]],[[-1,2],[-7,3]],[[0,0],[0,0]]]) {
 const c=S.oppositeCorner(a,b);
 assert.equal(Math.abs(c[0]-a[0]), Math.abs(c[1]-a[1]));
}
const lines=S.segments(bounds,bounds,16,110);
assert(lines.length>0 && lines.length<=114);
const big=S.bounds([[0,0,20000,30000]],1);
assert(S.segments(big,big,1,110).length <=114,'viewport line cap');
const ed=fs.readFileSync(path.resolve(__dirname,'../web/editor.js'),'utf8');
const viewer=fs.readFileSync(path.resolve(__dirname,'../web/app.js'),'utf8');
assert(ed.includes('rectMode = E.tool === "square" ? "square" : "fill"'));
assert(ed.includes('if (mode === "square") b = NodesSquare.oppositeCorner(a, b);'));
assert(ed.includes('onlyWild: E.opt.wild, landOnly: E.opt.land'));
assert(ed.includes('isLocked(target)'));
assert(viewer.includes('refreshSquareFrame(); buildTerritories()'));
console.log('PASS square bounds, sector grid capping, tool equal sides, legacy claimed-land safeguards');
