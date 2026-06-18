import unittest


class PlaceholderTest(unittest.TestCase):
    # The script pipelines currently have no unit tests, but `unittest discover`
    # returns exit code 5 ("no tests ran") on an empty suite, which fails CI.
    # This one trivial test keeps the suite non-empty so discovery exits cleanly;
    # delete it once real script tests exist.
    def test_placeholder(self):
        pass
