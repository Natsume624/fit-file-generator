// Deterministic network-failure regression checks for the shared map controller.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '../web/map-sources.js'), 'utf8');
const coordinates = fs.readFileSync(path.join(__dirname, '../web/map-coordinates.js'), 'utf8');

function setup() {
 const timers = new Map(), layers = [];
 let nextTimer = 0;
 const element = () => ({value:'',hidden:true,textContent:'',children:[],events:{},
  appendChild(child){this.children.push(child);},
  addEventListener(name,callback){this.events[name]=callback;}});
 const selector=element(), notice=element(), retry=element(), diagnostics=element();
 const mercator={bounds:{},project(point){return {x:point.lng,y:point.lat};},unproject(point){return {lat:point.y,lng:point.x};}};
 const standardCrs={projection:mercator};
 const center={lat:30.582,lng:114.332};
 const map={removed:[],views:[],options:{crs:standardCrs},
  getCenter(){return center;},getZoom(){return 18;},
  setView(point,zoom,options){this.views.push({point,zoom,options});},
  removeLayer(layer){this.removed.push(layer);}};
 const context={window:{},document:{createElement:element},Set,
  setTimeout(callback){timers.set(++nextTimer,callback);return nextTimer;},
  clearTimeout(id){timers.delete(id);},
  L:{Projection:{SphericalMercator:mercator},CRS:{EPSG3857:standardCrs},latLng(point){return {lat:point[0],lng:point[1]};},tileLayer(url,options){
   const layer={url,options,events:{},on(name,callback){this.events[name]=callback;return this;},
    emit(name){this.events[name]?.();},addTo(){this.emit('loading');return this;}};
   layers.push(layer);return layer;
  }}};
 vm.runInNewContext(coordinates,context);
 vm.runInNewContext(source,context);
 context.window.FitMapSources(map,selector,notice,retry,diagnostics);
 return {selector,notice,retry,diagnostics,layers,timers,map,standardCrs,
  timeout(){const [id,callback]=timers.entries().next().value;timers.delete(id);callback();},
  fail(){const layer=layers.at(-1);layer.emit('tileerror');layer.emit('load');},
  succeed(){const layer=layers.at(-1);layer.emit('tileload');layer.emit('load');}};
}

test('default map uses the domestic host without contacting overseas services',()=>{
 const state=setup();
 assert.equal(state.selector.value,'amap-imagery');
 assert.equal(state.layers.length,1);
 assert.match(state.layers[0].url,/is\.autonavi\.com/);
 state.succeed();
 assert.equal(state.notice.hidden,true);
 assert.equal(state.timers.size,0);
});

test('complete tile failure switches sources while a late old tile cannot clear the warning',()=>{
 const state=setup(),old=state.layers[0];
 state.fail();
 assert.equal(state.selector.value,'amap-streets');
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
 assert.equal(state.selector.value,'amap-streets');
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
 for(let i=0;i<state.selector.children.length;i++)state.fail();
 assert.equal(state.layers.length,5);
 assert.equal(state.timers.size,0);
 assert.match(state.notice.textContent,/所有底图均连接失败/);
 state.retry.events.click();
 assert.equal(state.layers.length,6);
 state.fail();
 assert.equal(state.selector.value,'amap-imagery');
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
 assert.equal(state.selector.value,'amap-streets');
});

test('domestic projection converts display points and returns clicks as WGS84',()=>{
 const state=setup(),center=state.map.getCenter();
 const projection=state.map.options.crs.projection;
 const screen=projection.project(center);
 assert.ok(Math.abs(screen.x-center.lng)>.001);
 const roundtrip=projection.unproject(screen);
 assert.ok(Math.abs(roundtrip.lat-center.lat)<1e-8);
 assert.ok(Math.abs(roundtrip.lng-center.lng)<1e-8);
 assert.equal(center.lat,30.582);
 assert.equal(center.lng,114.332);
});

test('switching between domestic and global maps preserves canonical center and zoom',()=>{
 const state=setup(),center=state.map.getCenter(),domesticCrs=state.map.options.crs;
 state.selector.value='imagery';state.selector.events.change();
 assert.equal(state.map.options.crs,state.standardCrs);
 state.selector.value='amap-streets';state.selector.events.change();
 assert.equal(state.map.options.crs,domesticCrs);
 assert.ok(state.map.views.every(view=>view.point===center&&view.zoom===18&&view.options.reset));
});

test('diagnostics record failed sources without disclosing selected coordinates',()=>{
 const state=setup();
 state.fail();state.succeed();
 assert.match(state.diagnostics.textContent,/高德卫星影像.*成功 0 \/ 失败 1/);
 assert.match(state.diagnostics.textContent,/高德街道地图.*加载正常/);
 assert.doesNotMatch(state.diagnostics.textContent,/30\.582|114\.332/);
});
