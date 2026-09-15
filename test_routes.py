import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from dataclasses import replace
from datetime import datetime
from fit_tool.fit_file import FitFile
from fit_tool.profile.messages.record_message import RecordMessage
from fit_tool.profile.messages.session_message import SessionMessage
from geographiclib.geodesic import Geodesic
from routes import load_gpx,make_route
from map_picker import MapPicker
from main import Run,generate_fit,track_position,to_wgs84,SOURCES

GPX = '''<?xml version="1.0"?><gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg><trkpt lat="30.58" lon="114.33"/><trkpt lat="30.581" lon="114.33"/><trkpt lat="30.581" lon="114.331"/></trkseg><trkseg><trkpt lat="30.59" lon="114.35"/><trkpt lat="30.591" lon="114.35"/></trkseg></trk></gpx>'''


class RouteTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory(dir=Path(__file__).parent)
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)/'sample.gpx'

    def test_segments_and_duplicate_points(self):
        self.path.write_text(GPX,encoding='utf8')
        routes=load_gpx(self.path)
        self.assertEqual(len(routes),2)
        self.assertEqual(routes[0].points[0],(30.58,114.33))
        self.assertLess(routes[0].length,300)
        route=make_route('duplicate',[(30,114),(30,114),(30.001,114)])
        self.assertEqual(len(route.points),2)
        self.assertEqual(route.position(-1),route.points[0])
        self.assertEqual(route.position(1e8),route.points[-1])

    def test_invalid_gpx_and_entities(self):
        for data in ['<gpx><trk>','<test/>','<!DOCTYPE gpx [<!ENTITY a "b">]><gpx/>','<gpx><trk><trkseg><trkpt lat="nan" lon="114"/><trkpt lat="31" lon="114"/></trkseg></trk></gpx>']:
            self.path.write_text(data,encoding='utf8')
            with self.assertRaises(ValueError):load_gpx(self.path)

    def test_coordinate_conversion_is_explicit(self):
        self.path.write_text(GPX,encoding='utf8')
        raw=load_gpx(self.path)[0]
        converted=load_gpx(self.path,lambda lat,lon:to_wgs84(lat,lon,SOURCES[0]))[0]
        self.assertGreater(Geodesic.WGS84.Inverse(*raw.points[0],*converted.points[0])['s12'],100)

    def test_gpx_to_fit_roundtrip(self):
        self.path.write_text(GPX,encoding='utf8')
        route=load_gpx(self.path)[0]
        run=Run(route.length,100,171,0,0,0,84.39,36.8,datetime(2026,9,15,8),route)
        self.assertEqual(track_position(run,0),route.points[0])
        self.assertEqual(track_position(run,run.distance),route.points[-1])
        fit=Path(self.temp.name)/'route.fit'
        generate_fit(run,fit)
        messages=[r.message for r in FitFile.from_file(str(fit)).records if not r.is_definition]
        points=[m for m in messages if isinstance(m,RecordMessage)]
        summary=next(m for m in messages if isinstance(m,SessionMessage))
        self.assertAlmostEqual(summary.total_distance,route.length,delta=.01)
        self.assertEqual(2*(summary.avg_cadence+summary.avg_fractional_cadence),171)
        for record,expected in [(points[0],route.points[0]),(points[-1],route.points[-1])]:
            self.assertLess(Geodesic.WGS84.Inverse(record.position_lat,record.position_long,*expected)['s12'],.02)


class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.applied=[]
        self.picker=MapPicker({},lambda values:[[values['lat'],values['lon']]],lambda values:self.applied.append(values) or True)
        self.addCleanup(self.picker.close)
        self.values=dict(lat=30.58,lon=114.33,bearing=62,straight=84.39,radius=36.8)

    def post(self,endpoint,values,origin=None):
        request=Request(self.picker.url+endpoint,data=json.dumps(values).encode(),headers={'Content-Type':'application/json','Origin':origin or 'http://'+self.picker.address})
        return json.load(urlopen(request))

    def test_static_preview_and_apply(self):
        with urlopen(self.picker.url) as r:self.assertIn(b'leaflet',r.read())
        self.assertEqual(self.post('preview',self.values)['points'],[[30.58,114.33]])
        self.assertTrue(self.post('apply',self.values)['ok'])
        self.assertEqual(len(self.applied),1)

    def test_origin_token_and_invalid_input(self):
        with self.assertRaises(HTTPError) as result:self.post('apply',self.values,'https://example.org')
        self.assertEqual(result.exception.code,403)
        with self.assertRaises(HTTPError):urlopen('http://'+self.picker.address+'/wrong/state')
        for values in [{},dict(self.values,lat=float('nan')),dict(self.values,radius=-1)]:
            with self.assertRaises(HTTPError) as result:self.post('apply',values)
            self.assertEqual(result.exception.code,400)
        self.assertFalse(self.applied)

    def test_gpx_cannot_be_overwritten_from_map(self):
        self.picker.state={'route':{'name':'sample'}}
        with self.assertRaises(HTTPError) as result:self.post('apply',self.values)
        self.assertEqual(result.exception.code,409)


if __name__=='__main__':unittest.main()
