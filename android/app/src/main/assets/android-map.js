'use strict';
const $=id=>document.getElementById(id);
const status=text=>{$('status').textContent=text;};
const map=L.map('map',{maxZoom:21,zoomControl:false}).setView([35,105],4);
L.control.zoom({position:'topright'}).addTo(map);
const tiles=L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxNativeZoom:19,maxZoom:21,attribution:'&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'}).addTo(map);
L.control.scale({imperial:false,position:'topright'}).addTo(map);
tiles.on('tileerror',()=>{$('offline').hidden=false;});
tiles.on('tileload',()=>{$('offline').hidden=true;});
const line=L.polyline([],{color:'#2563eb',weight:5,opacity:.86,smoothFactor:0}).addTo(map);
let selected=null,marker=null,routeMode=false;
function values(){return {lat:selected.lat,lon:selected.lng,bearing:Number($('bearing').value),straight:Number($('straight').value),radius:Number($('radius').value)};}
function localPoint(distance,straight,radius,bearing){const arc=Math.PI*radius,s=((distance%(2*straight+2*arc))+2*straight+2*arc)%(2*straight+2*arc);let x,y;if(s<straight){x=-radius;y=-straight/2+s;}else if(s<straight+arc){const a=Math.PI-(s-straight)/radius;x=radius*Math.cos(a);y=straight/2+radius*Math.sin(a);}else if(s<2*straight+arc){x=radius;y=straight/2-(s-straight-arc);}else{const a=(s-2*straight-arc)/radius;x=radius*Math.cos(a);y=-straight/2-radius*Math.sin(a);}const a=bearing*Math.PI/180;return [x*Math.cos(a)+y*Math.sin(a),-x*Math.sin(a)+y*Math.cos(a)];}
function redraw(){if(!selected||routeMode)return;const v=values(),length=2*v.straight+2*Math.PI*v.radius,cos=Math.cos(v.lat*Math.PI/180),points=[];for(let i=0;i<=180;i++){const [east,north]=localPoint(length*i/180,v.straight,v.radius,v.bearing);points.push([v.lat+north/111320,v.lon+east/(111320*Math.max(.05,cos))]);}line.setLatLngs(points);$('angleText').textContent=v.bearing.toFixed(1)+'°';$('summary').textContent=`周长 ${length.toFixed(2)} 米\n纬度 ${v.lat.toFixed(7)} · 经度 ${v.lon.toFixed(7)}`;}
function choose(point){if(routeMode)return;selected=L.latLng(point);if(!marker){marker=L.marker(selected,{draggable:true,icon:L.divIcon({className:'center-pin',iconSize:[16,16],iconAnchor:[8,8]})}).addTo(map);marker.on('drag',()=>{selected=marker.getLatLng();redraw();});}else marker.setLatLng(selected);$('apply').disabled=false;redraw();}
map.on('click',e=>{choose(e.latlng);status('已选中心，可拖动蓝点继续微调。');});
for(const id of ['bearing','straight','radius'])$(id).addEventListener('input',redraw);
$('place').onclick=()=>{choose(map.getCenter());status('已移到当前画面中心。');};
$('apply').onclick=()=>{if(!selected)return status('请先选择跑道中心。');AndroidBridge.apply(JSON.stringify(values()));};
$('searchForm').onsubmit=e=>{e.preventDefault();const q=$('query').value.trim();if(q.length<2)return status('请至少输入两个字。');$('searchButton').disabled=true;$('results').replaceChildren();status('正在查找…');AndroidBridge.search(q);};
window.receiveSearch=results=>{$('searchButton').disabled=false;for(const item of results){const button=document.createElement('button');button.type='button';button.textContent=item.label;button.onclick=()=>{map.setView([item.lat,item.lon],17);status('已定位附近，请点击实际跑道中心。');};$('results').appendChild(button);}status(results.length?'选择结果后，在地图上点选跑道中心。':'未找到地点，请换个关键词。');};
window.searchFailed=message=>{$('searchButton').disabled=false;status(message);};
window.initFromAndroid=state=>{routeMode=Boolean(state.route);if(routeMode){$('hero').textContent='查看导入的 GPX 路线。';$('intro').textContent='路线仅在本机显示，不会上传到地图或搜索服务。';$('editor').hidden=true;$('searchForm').hidden=true;$('routeInfo').hidden=false;$('routeName').textContent=state.route.name+` · ${(state.route.length/1000).toFixed(3)} km`;line.setLatLngs(state.route.points);map.fitBounds(line.getBounds(),{padding:[30,30],maxZoom:19});L.circleMarker(state.route.points[0],{radius:6,color:'#fff',fillColor:'#22a06b',fillOpacity:1}).addTo(map);L.circleMarker(state.route.points[state.route.points.length-1],{radius:6,color:'#fff',fillColor:'#e36b4e',fillOpacity:1}).addTo(map);status('绿色为起点，橙色为终点。');}else{for(const key of ['bearing','straight','radius'])$(key).value=state[key];$('apply').disabled=true;if(state.lat!==null&&state.lon!==null){map.setView([state.lat,state.lon],18);choose([state.lat,state.lon]);}status('搜索地点或缩放地图，在跑道中央点选。');}setTimeout(()=>map.invalidateSize(),100);};
