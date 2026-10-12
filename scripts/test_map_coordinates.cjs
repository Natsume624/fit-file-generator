const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const {test}=require('node:test');
const context={window:{}};
vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../web/map-coordinates.js'),'utf8'),context);
const {toGcj,toWgs}=context.window.FitMapCoordinates;

// Reference fixtures from coordTransform, the existing desktop import converter.
const fixtures=[
 {wgs:[30.582,114.332],gcj:[30.57963567191256,114.33748527890792]},
 {wgs:[39.908823,116.39747],gcj:[39.91022649807321,116.4037135824225]},
 {wgs:[31.230416,121.473701],gcj:[31.22847374224698,121.47822405739267]},
 {wgs:[23.12911,113.264385],gcj:[23.1264333357339,113.26971458275315]},
 {wgs:[43.8256,87.6168],gcj:[43.82680539311119,87.61964994946926]},
 {wgs:[22.5431,114.0579],gcj:[22.54038281422246,114.06301399856547]}
];
test('display conversion agrees with the independent desktop converter',()=>{
 for(const fixture of fixtures){
  const actual=toGcj(fixture.wgs);
  assert.ok(Math.abs(actual[0]-fixture.gcj[0])<1e-10);
  assert.ok(Math.abs(actual[1]-fixture.gcj[1])<1e-10);
 }
});
test('clicks on the domestic map convert back to the original WGS84 position',()=>{
 for(const fixture of fixtures){
  const actual=toWgs(fixture.gcj);
  assert.ok(Math.abs(actual[0]-fixture.wgs[0])<1e-8);
  assert.ok(Math.abs(actual[1]-fixture.wgs[1])<1e-8);
 }
});
test('locations outside the conversion region remain unchanged',()=>{
 for(const point of [[51,-.1],[-33.87,151.21],[40.71,-74.01]]){
  assert.deepEqual(Array.from(toGcj(point)),point);
  assert.deepEqual(Array.from(toWgs(point)),point);
 }
});
test('display conversion never mutates GPX point arrays',()=>{
 for(const fixture of fixtures){
  const point=Object.freeze([...fixture.wgs]);
  toGcj(point);toWgs(point);
  assert.deepEqual(point,fixture.wgs);
 }
});
