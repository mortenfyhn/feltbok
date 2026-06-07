"""Tests for the locality-table heuristics in build_localities.py.

These cover the pure logic that decides which localities survive - the part that
is fiddly and easy to regress. Run with: .venv/bin/python -m pytest scripts/
(or: .venv/bin/python -m unittest scripts/test_build_localities.py)
"""

import importlib.util
import pathlib
import unittest

_spec = importlib.util.spec_from_file_location(
    "build_localities", pathlib.Path(__file__).parent / "build_localities.py"
)
bl = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bl)


def site(lok, lat, lon, count, *, id="0", hoved="", kommune="Frøya", fylke="Trøndelag"):
    """A final-shape row: [id, lok, hoved, kommune, fylke, lat, lon, count]."""
    return [id, lok, hoved, kommune, fylke, lat, lon, count]


class Observers(unittest.TestCase):
    def test_splits_on_pipe_and_dedups(self):
        self.assertEqual(bl.observers("Ola|Kari|Ola"), {"Ola", "Kari"})

    def test_blank_is_unknown_singleton(self):
        self.assertEqual(bl.observers(""), {"?"})
        self.assertEqual(bl.observers(None), {"?"})

    def test_accepts_list(self):
        self.assertEqual(bl.observers(["Ola", "Kari"]), {"Ola", "Kari"})


class SplitName(unittest.TestCase):
    def test_full_qualified_drops_kommune_and_fylke(self):
        self.assertEqual(
            bl.split_name("Ørndalen, Sistranda, Frøya, Tø"), ("Ørndalen", "Sistranda")
        )

    def test_three_parts_keeps_only_lokalitet(self):
        self.assertEqual(bl.split_name("Sula, Frøya, Tø"), ("Sula", ""))

    def test_doubled_superlok_is_part_of_the_name(self):
        # Sørøyan's registered name literally ends in its superlokalitet, so GBIF
        # repeats it; the match key is the full "Sørøyan, Uttian" (verified live).
        self.assertEqual(
            bl.split_name("Sørøyan, Uttian, Uttian, Frøya, Tø"),
            ("Sørøyan, Uttian", "Uttian"),
        )

    def test_deeper_hierarchy_keeps_plain_name(self):
        # No doubled tail -> we can't tell name from extra levels; keep first token.
        self.assertEqual(
            bl.split_name("Sistrandfjæra, Sistranda bedehus, Sistranda, Frøya, Tø"),
            ("Sistrandfjæra", "Sistranda bedehus"),
        )

    def test_two_parts_keeps_first(self):
        self.assertEqual(bl.split_name("Titran, Tø"), ("Titran", ""))

    def test_bare_name(self):
        self.assertEqual(bl.split_name("Sula"), ("Sula", ""))


class Haversine(unittest.TestCase):
    def test_zero_distance(self):
        self.assertAlmostEqual(bl.haversine(63.7, 8.8, 63.7, 8.8), 0.0, places=6)

    def test_one_degree_latitude_is_about_111km(self):
        d = bl.haversine(63.0, 8.0, 64.0, 8.0)
        self.assertTrue(110 < d < 112, d)


class DropNameCollisions(unittest.TestCase):
    def test_unique_name_always_kept_even_if_tiny(self):
        rows = [site("Lonely", 63.70, 8.50, 2)]
        self.assertEqual(bl.drop_name_collisions(rows), rows)

    def test_all_low_count_cluster_is_dropped(self):
        # A private route: same name, many nearby points, none popular.
        rows = [
            site("Route", 63.80, 8.39, 5),
            site("Route", 63.81, 8.39, 4),
            site("Route", 63.82, 8.40, 3),
        ]
        self.assertEqual(bl.drop_name_collisions(rows), [])

    def test_dominant_public_site_kept_nearby_fragments_dropped(self):
        big = site("Myra", 63.75, 8.69, 163, id="big")
        small = site("Myra", 63.753, 8.695, 7, id="small")  # ~0.4 km away
        kept = bl.drop_name_collisions([small, big])
        self.assertEqual([r[0] for r in kept], ["big"])

    def test_distant_low_count_sibling_also_dropped(self):
        big = site("Myra", 63.75, 8.69, 163, id="big")
        far_small = site("Myra", 63.64, 8.50, 7, id="far")  # far but still low
        kept = bl.drop_name_collisions([big, far_small])
        self.assertEqual([r[0] for r in kept], ["big"])

    def test_two_distant_popular_sites_both_kept(self):
        a = site("Stormyra", 63.75, 8.69, 50, id="a")
        b = site("Stormyra", 63.64, 8.95, 60, id="b")  # >2 km, also popular
        kept = sorted(r[0] for r in bl.drop_name_collisions([a, b]))
        self.assertEqual(kept, ["a", "b"])

    def test_two_popular_but_close_collapse_to_one(self):
        a = site("Vatnet", 63.7500, 8.6900, 50, id="a")
        b = site("Vatnet", 63.7505, 8.6905, 60, id="b")  # <2 km, both popular
        kept = bl.drop_name_collisions([a, b])
        self.assertEqual([r[0] for r in kept], ["b"])  # higher count wins


if __name__ == "__main__":
    unittest.main()
