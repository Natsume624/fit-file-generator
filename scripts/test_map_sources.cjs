// Deterministic network-failure regression checks for the shared map controller.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '../web/map-sources.js'), 'utf8');

function setup() {
 const timers = new Map(), layers = [];
 let nextTimer = 0;
 const element = () => ({value:'',hidden:true,textContent:'',children:[],events:{},
  appendChild(child){this.children.push(child);},
  addEventListener(name,callback){this.events[name]=callback;}});
 const selector=element(), notice=element(), retry=element();
 const map={removed:[],removeLayer(layer){this.removed.push(layer);}};
 const context={window:{},document:{createElement:element},Set,
  setTimeout(callback){timers.set(++nextTimer,callback);return nextTimer;},
  clearTimeout(id){timers.delete(id);},
  L:{tileLayer(url,options){
   const layer={url,options,events:{},on(name,callback){this.events[name]=callback;return this;},
    emit(name){this.events[name]?.();},addTo(){this.emit('loading');return this;}};
   layers.push(layer);return layer;
  }}};
 vm.runInNewContext(source,context);
 context.window.FitMapSources(map,selector,notice,retry);
 return {selector,notice,retry,layers,timers,map,
  timeout(){const [id,callback]=timers.entries().next().value;timers.delete(id);callback();},
  fail(){const layer=layers.at(-1);layer.emit('tileerror');layer.emit('load');},
  succeed(){const layer=layers.at(-1);layer.emit('tileload');layer.emit('load');}};
}

test('default map loads without requesting the blocked OSM host',()=>{
 const state=setup();
 assert.equal(state.selector.value,'imagery');
 assert.equal(state.layers.length,1);
 assert.match(state.layers[0].url,/services\.arcgisonline\.com/);
 state.succeed();
 assert.equal(state.notice.hidden,true);
 assert.equal(state.timers.size,0);
});

test('complete tile failure switches sources while a late old tile cannot clear the warning',()=>{
 const state=setup(),old=state.layers[0];
 state.fail();
 assert.equal(state.selector.value,'streets');
 assert.deepEqual(state.map.removed,[old]);
 assert.equal(state.notice.hidden,false);
 old.emit('tileload');old.emit('load');
 assert.equal(state.notice.hidden,false);
 state.succeed();
 assert.equal(state.notice.hidden,true);
});

test('hanging requests time out and try another source',()=>{
 const state=setup();
 state.timeout();
 assert.equal(state.selector.value,'streets');
 assert.equal(state.layers.length,2);
});

test('one loaded tile does not hide other tile errors or trigger a source switch',()=>{
 const state=setup(),layer=state.layers[0];
 layer.emit('tileerror');layer.emit('tileload');
 layer.emit('load');
 assert.equal(state.layers.length,1);
 assert.equal(state.notice.hidden,false);
 assert.match(state.notice.textContent,/部分底图/);
});

test('all unavailable sources stop retrying; explicit retry starts a fresh attempt',()=>{
 const state=setup();
 state.fail();state.fail();state.fail();
 assert.equal(state.layers.length,3);
 assert.equal(state.timers.size,0);
 assert.match(state.notice.textContent,/底图暂不可用/);
 state.retry.events.click();
 assert.equal(state.layers.length,4);
 state.fail();
 assert.equal(state.selector.value,'imagery');
});

test('manual switching cancels the old timer and preserves the selected map after success',()=>{
 const state=setup();
 state.selector.value='osm';state.selector.events.change();
 assert.equal(state.layers.length,2);
 assert.equal(state.timers.size,1);
 state.succeed();
 assert.equal(state.selector.value,'osm');
 assert.equal(state.timers.size,0);
});

test('later viewport failures are detected after the first view has loaded',()=>{
 const state=setup();
 state.succeed();
 state.layers[0].emit('loading');
 state.timeout();
 assert.equal(state.selector.value,'streets');
});
