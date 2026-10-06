"""Template data: the dev/train split must not move when templates change.  Run: uv run python -m unittest discover -s tests"""

import unittest

from pebble_brain.pebble_data import EVAL_FILES, generate, split_of


class SplitTest(unittest.TestCase):
    def test_split_depends_only_on_the_sentence(self):
        self.assertEqual(split_of("remind me to call mom tomorrow"), split_of("remind me to call mom tomorrow"))
        parts = [split_of(f"sentence number {i}") for i in range(2000)]
        share = parts.count("dev") / len(parts)
        self.assertTrue(0.07 < share < 0.13, share)

    def test_generate_assigns_the_hash_split(self):
        examples = generate(per_template=2)
        self.assertTrue(examples)
        for e in examples:
            self.assertEqual(e.partition, split_of(" ".join(e.tokens).lower()))

    def test_eval_files_are_listed_once(self):
        self.assertEqual(len(set(EVAL_FILES)), len(EVAL_FILES))


if __name__ == "__main__":
    unittest.main()
