import unittest

from backend.app.viewshed import compute_viewshed, haversine_m


class ViewshedBackendTest(unittest.TestCase):
    def test_target_height_does_not_become_a_terrain_obstacle(self):
        result = compute_viewshed(
            observer_lat=0.0,
            observer_lon=0.0,
            eye_height_m=1.7,
            target_height_m=10.0,
            max_distance_m=1_000.0,
            num_rays=8,
            samples_per_ray=10,
            use_curvature=False,
            elevation_fn=lambda _lat, _lon: 0.0,
        )

        properties = result["features"][0]["properties"]
        self.assertEqual(properties["total_cells"], properties["visible_cells"])
        self.assertTrue(all(distance == 1_000.0 for distance in properties["ranges_m"]))

    def test_hidden_valley_is_not_filled_to_a_visible_far_peak(self):
        observer_lat = 0.0
        observer_lon = 0.0

        def terrain(lat, lon):
            distance = haversine_m(observer_lat, observer_lon, lat, lon)
            if 250.0 <= distance <= 350.0:
                return 50.0
            if distance >= 950.0:
                return 200.0
            return 0.0

        result = compute_viewshed(
            observer_lat=observer_lat,
            observer_lon=observer_lon,
            eye_height_m=2.0,
            target_height_m=0.0,
            max_distance_m=1_000.0,
            num_rays=8,
            samples_per_ray=10,
            use_curvature=False,
            elevation_fn=terrain,
        )

        feature = result["features"][0]
        properties = feature["properties"]
        first_ray = [
            sector for sector in properties["sectors"] if sector["ray_index"] == 0
        ]
        self.assertEqual("MultiPolygon", feature["geometry"]["type"])
        self.assertEqual(2, len(first_ray))
        self.assertEqual(300.0, first_ray[0]["outer_distance_m"])
        self.assertEqual(900.0, first_ray[1]["inner_distance_m"])
        self.assertEqual(1_000.0, first_ray[1]["outer_distance_m"])
        self.assertLess(properties["visible_cells"], properties["total_cells"])

    def test_demo_response_has_valid_closed_multipolygon_rings(self):
        result = compute_viewshed(
            observer_lat=41.5,
            observer_lon=-74.0,
            max_distance_m=500.0,
            num_rays=8,
            samples_per_ray=10,
        )

        geometry = result["features"][0]["geometry"]
        self.assertEqual("MultiPolygon", geometry["type"])
        self.assertTrue(geometry["coordinates"])
        for polygon in geometry["coordinates"]:
            self.assertEqual(polygon[0][0], polygon[0][-1])


if __name__ == "__main__":
    unittest.main()
