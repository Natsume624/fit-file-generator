'use strict';

// Shared by desktop and Android. The custom CRS projects WGS84 into GCJ-02
// for domestic tiles, then inverts clicks back into WGS84 automatically.
window.FitMapSources = function (map, selector, notice, retry, diagnostics) {
 const osmCredit='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';
 const amapCredit='&copy; <a href="https://www.amap.com/">高德地图</a>';
 const sources=[
  {id:'amap-imagery',name:'高德卫星影像（国内）',gcj:true,url:'https://webst0{s}.is.autonavi.com/appmaptile?style=6&lang=zh_cn&size=1&scale=1&x={x}&y={y}&z={z}',subdomains:['1','2'],credit:amapCredit},
  {id:'amap-streets',name:'高德街道地图（国内）',gcj:true,url:'https://wprd0{s}.is.autonavi.com/appmaptile?style=7&lang=zh_cn&size=1&scale=1&x={x}&y={y}&z={z}',subdomains:['1','2','3','4'],credit:amapCredit},
  {id:'imagery',name:'Esri 卫星影像',url:'https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',credit:'Source: Esri, Vantor, Earthstar Geographics, and the GIS User Community'},
  {id:'streets',name:'Esri 街道地图',url:'https://services.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}',credit:'Sources: Esri, HERE, Garmin, USGS, Intermap, INCREMENT P, NRCan, Esri Japan, METI, Esri China (Hong Kong), Esri Korea, Esri (Thailand), NGCC, '+osmCredit+', and the GIS User Community'},
  {id:'osm',name:'OpenStreetMap',url:'https://tile.openstreetmap.org/{z}/{x}/{y}.png',credit:osmCredit}
 ];
 const mercator=L.Projection.SphericalMercator;
 const gcjCrs=Object.assign({},L.CRS.EPSG3857,{
  code:'GCJ02:3857',
  projection:{
   bounds:mercator.bounds,
   project(point){return mercator.project(L.latLng(window.FitMapCoordinates.toGcj([point.lat,point.lng])));},
   unproject(point){const gcj=mercator.unproject(point);return L.latLng(window.FitMapCoordinates.toWgs([gcj.lat,gcj.lng]));}
  }
 });
 let active=null,timer=null,loaded=0,errors=0;
 const attempted=new Set();
 const reports=new Map();
 function report(id,reason='') {
  reports.set(id,{loaded,errors,reason});
  if(diagnostics)diagnostics.textContent=sources.map(source=>{
   const entry=reports.get(source.id);
   return source.name+'：'+(entry?'成功 '+entry.loaded+' / 失败 '+entry.errors+(entry.reason?' · '+entry.reason:''):'未尝试');
  }).join('\n')+'\n应用内坐标与 FIT：WGS84\n国内底图显示：GCJ-02（自动转换）';
 }
 const message=text=>{notice.textContent=text;notice.hidden=!text;};
 const stopTimer=()=>{clearTimeout(timer);timer=null;};
 function unavailable(layer,reason) {
  if(active!==layer)return;
  stopTimer();
  report(selector.value,reason);
  const next=sources.find(source=>!attempted.has(source.id));
  if(next)activate(next.id,true);
  else message('所有底图均连接失败。可复制“底图连接诊断”反馈，或重试 / 切换地图。');
 }
 function activate(id,automatic=false) {
  const source=sources.find(item=>item.id===id);
  if(!source)return;
  stopTimer();
  const previous=active;
  active=null;
  if(previous)map.removeLayer(previous);
  if(!automatic)attempted.clear();
  attempted.add(id);
  selector.value=id;
  loaded=0;errors=0;
  report(id,'正在加载');
  message(automatic?'底图连接失败，正在切换至 '+source.name+'…':'');
  // Reset the projection at the same WGS84 center. Leaflet's viewreset updates
  // existing polylines and markers too, without rewriting their coordinates.
  const center=map.getCenter(),zoom=map.getZoom();
  const crs=source.gcj?gcjCrs:L.CRS.EPSG3857;
  if(map.options.crs!==crs){map.options.crs=crs;map.setView(center,zoom,{animate:false,reset:true});}
  const layer=L.tileLayer(source.url,{maxNativeZoom:19,maxZoom:21,attribution:source.credit,subdomains:source.subdomains||'abc'});
  active=layer;
  layer.on('loading',()=>{
   if(active!==layer)return;
   loaded=0;errors=0;stopTimer();
   timer=setTimeout(()=>{
    if(active!==layer)return;
    if(!loaded)unavailable(layer,'8 秒未加载到瓦片');
    else {report(id,'部分请求超过 8 秒');message('部分底图加载较慢，可重试或切换地图。');}
   },8000);
  });
  layer.on('tileload',()=>{if(active===layer){loaded++;report(id);}});
  layer.on('tileerror',()=>{if(active===layer){errors++;report(id,'瓦片请求失败');}});
  layer.on('load',()=>{
   if(active!==layer)return;
   stopTimer();
   if(!loaded)unavailable(layer,'瓦片请求全部失败');
   else {report(id,errors?'部分瓦片失败':'加载正常');message(errors?'部分底图未加载，可重试或切换地图。':'');}
  });
  layer.addTo(map);
 }
 for(const source of sources){
  const option=document.createElement('option');
  option.value=source.id;option.textContent=source.name;selector.appendChild(option);
 }
 selector.addEventListener('change',()=>activate(selector.value));
 retry.addEventListener('click',()=>activate(selector.value));
 activate(sources[0].id);
 return {activate};
};
