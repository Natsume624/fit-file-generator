'use strict';

// Shared by the desktop browser and Android WebView. All sources use WGS84
// Web Mercator, so switching the basemap never changes route coordinates.
window.FitMapSources = function (map, selector, notice, retry) {
 const osmCredit='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';
 const sources=[
  {id:'imagery',name:'Esri 卫星影像',url:'https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',credit:'Source: Esri, Vantor, Earthstar Geographics, and the GIS User Community'},
  {id:'streets',name:'Esri 街道地图',url:'https://services.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}',credit:'Sources: Esri, HERE, Garmin, USGS, Intermap, INCREMENT P, NRCan, Esri Japan, METI, Esri China (Hong Kong), Esri Korea, Esri (Thailand), NGCC, '+osmCredit+', and the GIS User Community'},
  {id:'osm',name:'OpenStreetMap',url:'https://tile.openstreetmap.org/{z}/{x}/{y}.png',credit:osmCredit}
 ];
 let active=null,timer=null,loaded=0,errors=0;
 const attempted=new Set();
 const message=text=>{notice.textContent=text;notice.hidden=!text;};
 const stopTimer=()=>{clearTimeout(timer);timer=null;};
 function unavailable(layer) {
  if(active!==layer)return;
  stopTimer();
  const next=sources.find(source=>!attempted.has(source.id));
  if(next)activate(next.id,true);
  else message('底图暂不可用。请重试或切换地图；跑道和 GPX 仍可查看。');
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
  message(automatic?'底图连接失败，正在切换至 '+source.name+'…':'');
  const layer=L.tileLayer(source.url,{maxNativeZoom:19,maxZoom:21,attribution:source.credit});
  active=layer;
  layer.on('loading',()=>{
   if(active!==layer)return;
   loaded=0;errors=0;stopTimer();
   timer=setTimeout(()=>{
    if(active!==layer)return;
    if(!loaded)unavailable(layer);
    else message('部分底图加载较慢，可重试或切换地图。');
   },8000);
  });
  layer.on('tileload',()=>{if(active===layer)loaded++;});
  layer.on('tileerror',()=>{if(active===layer)errors++;});
  layer.on('load',()=>{
   if(active!==layer)return;
   stopTimer();
   if(!loaded)unavailable(layer);
   else message(errors?'部分底图未加载，可重试或切换地图。':'');
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
