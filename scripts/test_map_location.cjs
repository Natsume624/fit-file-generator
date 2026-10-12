const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const {test}=require('node:test');
function setup(lat,lon,source){
 const context={window:{}};
 for(const file of ['map-coordinates.js','map-location.js'])vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../web',file),'utf8'),context);
 const form={elements:{lat:{value:lat},lon:{value:lon},source:{value:source}}},views=[],messages=[];
 context.window.FitMapLocation({setView(point,zoom){views.push({point,zoom});}},form,text=>messages.push(text));
 form.onsubmit({preventDefault(){}});
 return {views,messages};
}
test('manual GCJ-02 coordinates locate the map at their WGS84 position without search',()=>{
 const {views}=setup('30.57963567191256','114.33748527890792','gcj');
 assert.equal(views.length,1);
 assert.ok(Math.abs(views[0].point[0]-30.582)<1e-8);
 assert.ok(Math.abs(views[0].point[1]-114.332)<1e-8);
 assert.equal(views[0].zoom,18);
});
test('WGS84 input is not converted twice',()=>{
 const {views}=setup('30.582','114.332','wgs');
 assert.deepEqual(Array.from(views[0].point),[30.582,114.332]);
});
test('invalid and empty inputs do not move the map',()=>{
 for(const [lat,lon] of [['','114'],['nan','114'],['90','114'],['30','999']]){
  const {views,messages}=setup(lat,lon,'gcj');
  assert.equal(views.length,0);
  assert.match(messages[0],/有效坐标/);
 }
});
