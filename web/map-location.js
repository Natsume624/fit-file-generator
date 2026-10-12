'use strict';

// A local alternative when overseas place-search services cannot be reached.
window.FitMapLocation = function (map, form, status) {
 form.onsubmit=event=>{
  event.preventDefault();
  const rawLat=form.elements.lat.value.trim(),rawLon=form.elements.lon.value.trim();
  const lat=Number(rawLat),lon=Number(rawLon);
  if(!rawLat||!rawLon||!Number.isFinite(lat)||!Number.isFinite(lon)||lat<-85||lat>85||lon<-180||lon>180){
   status('请填写有效坐标：纬度 -85～85，经度 -180～180。');return;
  }
  const point=form.elements.source.value==='gcj'?window.FitMapCoordinates.toWgs([lat,lon]):[lat,lon];
  map.setView(point,18);
  status('已按坐标定位。请在实际跑道中央点选，再应用校准。');
 };
};
