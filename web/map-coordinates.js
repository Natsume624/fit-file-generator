'use strict';

// Local display projection only. Canonical route and exported FIT coordinates
// remain WGS84; no points are sent to a remote coordinate-conversion service.
window.FitMapCoordinates = (() => {
 const radians=Math.PI/180,axis=6378245,eccentricity=.006693421622965943;
 function toGcj(point) {
  const [lat,lon]=point;
  if(lon<72.004||lon>137.8347||lat<.8293||lat>55.8271)return [lat,lon];
  const x=lon-105,y=lat-35;
  const waves=(20*Math.sin(6*x*Math.PI)+20*Math.sin(2*x*Math.PI))*2/3;
  let north=-100+2*x+3*y+.2*y*y+.1*x*y+.2*Math.sqrt(Math.abs(x))+waves;
  north+=(20*Math.sin(y*Math.PI)+40*Math.sin(y*Math.PI/3))*2/3;
  north+=(160*Math.sin(y*Math.PI/12)+320*Math.sin(y*Math.PI/30))*2/3;
  let east=300+x+2*y+.1*x*x+.1*x*y+.1*Math.sqrt(Math.abs(x))+waves;
  east+=(20*Math.sin(x*Math.PI)+40*Math.sin(x*Math.PI/3))*2/3;
  east+=(150*Math.sin(x*Math.PI/12)+300*Math.sin(x*Math.PI/30))*2/3;
  const sine=Math.sin(lat*radians),magic=1-eccentricity*sine*sine,root=Math.sqrt(magic);
  north=north*180/(axis*(1-eccentricity)/(magic*root)*Math.PI);
  east=east*180/(axis/root*Math.cos(lat*radians)*Math.PI);
  return [lat+north,lon+east];
 }
 function toWgs(point) {
  const [lat,lon]=point;
  let guess=[lat,lon];
  for(let i=0;i<12;i++){
   const mapped=toGcj(guess),north=mapped[0]-lat,east=mapped[1]-lon;
   guess=[guess[0]-north,guess[1]-east];
   if(Math.max(Math.abs(north),Math.abs(east))<1e-10)break;
  }
  return guess;
 }
 return {toGcj,toWgs};
})();
