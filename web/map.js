'use strict';
const $=id=>document.getElementById(id);
const status=text=>{$('status').textContent=text;};
const map=L.map('map',{maxZoom:21}).setView([35,105],4);
const tiles=L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxNativeZoom:19,maxZoom:21,attribution:'&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank">OpenStreetMap</a> contributors'}).addTo(map);
L.control.scale({imperial:false}).addTo(map);
tiles.on('tileerror',()=>{$('offline').hidden=false;});
tiles.on('tileload',()=>{$('offline').hidden=true;});
let selected=null,marker=null,routeMode=false,previewTimer=null,revision=0;
const line=L.polyline([],{color:'#2563eb',weight:5,opacity:.85,smoothFactor:0}).addTo(map);
function values(){return {lat:selected.lat,lon:selected.lng,bearing:Number($('bearing').value),straight:Number($('straight').value),radius:Number($('radius').value)};}
async function post(endpoint,data){const r=await fetch(endpoint,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)});const result=await r.json();if(!r.ok)throw Error(result.error||'请求失败');return result;}
function updateSummary(){if(!selected)return;const v=values();$('angleText').textContent=v.bearing.toFixed(1)+'°';$('summary').textContent=`周长 ${(2*v.straight+2*Math.PI*v.radius).toFixed(2)} 米\n纬度 ${v.lat.toFixed(7)} / 经度 ${v.lon.toFixed(7)}`;$('summary').style.whiteSpace='pre-line';}
function schedule(){revision++;clearTimeout(previewTimer);updateSummary();if(!selected||routeMode)return;const requestRevision=revision;previewTimer=setTimeout(async()=>{try{const data=await post('preview',values());if(requestRevision===revision)line.setLatLngs(data.points);}catch(e){status(e.message);}},100);}
function choose(point){if(routeMode)return;selected=L.latLng(point);if(!marker){marker=L.marker(selected,{draggable:true,icon:L.divIcon({className:'center-pin',iconSize:[16,16],iconAnchor:[8,8]})}).addTo(map);marker.on('drag',()=>{selected=marker.getLatLng();schedule();});}else marker.setLatLng(selected);$('apply').disabled=false;schedule();}
map.on('click',e=>{choose(e.latlng);if(!routeMode)status('已选中心。可拖动蓝点、调整方向和尺寸，再点击应用。');});
for(const id of ['bearing','straight','radius'])$(id).addEventListener('input',schedule);
$('place').onclick=()=>{choose(map.getCenter());status('已移至画面中心。');};
$('apply').onclick=async()=>{if(!selected)return status('请先在地图上选点。');$('apply').disabled=true;try{await post('apply',values());status('已应用。请返回桌面程序查看并生成文件。');}catch(e){status(e.message);}finally{$('apply').disabled=false;}};
$('searchForm').onsubmit=async e=>{e.preventDefault();const q=$('query').value.trim();if(q.length<2)return status('请至少输入两个字。');$('searchButton').disabled=true;$('results').replaceChildren();status('正在查找…');try{const r=await fetch('search?q='+encodeURIComponent(q));const data=await r.json();if(!r.ok||data.error)throw Error(data.error||'搜索失败');for(const item of data.results){const b=document.createElement('button');b.type='button';b.textContent=item.label;b.onclick=()=>{map.setView([item.lat,item.lon],17);status('已定位附近。请放大地图，点击实际跑道中心。');};$('results').appendChild(b);}status(data.results.length?'选择搜索结果，再在地图上点选跑道。':'未找到。可尝试城市名，或直接缩放地图寻找。');}catch(e){status(e.message);}finally{$('searchButton').disabled=false;}};
async function init(){
 try{
  const response=await fetch('state');
  if(!response.ok)throw Error('连接已失效，请从桌面程序重新打开地图。');
  const state=await response.json();routeMode=Boolean(state.route);
  if(routeMode){
   $('hero').innerHTML='查看导入的<br>GPX 路线。';
   $('intro').textContent='查看路线与地图的相对位置，返回桌面设置时长与平均步频。';
   $('editor').hidden=true;$('routeInfo').hidden=false;
   $('routeName').textContent=state.route.name+` · ${(state.route.length/1000).toFixed(3)} km`;
   line.setLatLngs(state.route.points);map.fitBounds(line.getBounds(),{padding:[35,35],maxZoom:19});
   L.circleMarker(state.route.points[0],{radius:6,color:'#fff',fillColor:'#22a06b',fillOpacity:1}).addTo(map);
   L.circleMarker(state.route.points.at(-1),{radius:6,color:'#fff',fillColor:'#e36b4e',fillOpacity:1}).addTo(map);
   status('绿色为起点，橙色为终点。原轨迹保持不变。');
  }else{
   for(const key of ['bearing','straight','radius'])$(key).value=state[key];
   $('apply').disabled=true;
   if(state.lat!==null&&state.lon!==null){map.setView([state.lat,state.lon],18);choose([state.lat,state.lon]);}
   status('搜索地点或缩放地图，在跑道中央点击选点。');
  }
 }catch(e){status(e.message);}
}
init();
