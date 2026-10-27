import unittest
from preview_notes import render_notes

class PreviewNotesTest(unittest.TestCase):
    def test_metadata_and_single_summary(self):
        env=dict(GITHUB_SHA="a"*40, GITHUB_RUN_ID="123", GITHUB_REPOSITORY="wxcvm/AdAway")
        text=render_notes("# Preview changes\n\nCoherent statistics.\n","6.7.6-preview.39",env)
        self.assertEqual(text.count("Coherent statistics."),1)
        self.assertIn("/commit/"+"a"*40,text)
        self.assertIn("/actions/runs/123",text)
        self.assertIn("6.7.6-preview.39",text)
    def test_missing_or_malformed_inputs_fail_before_publication(self):
        env=dict(GITHUB_SHA="a"*40, GITHUB_RUN_ID="123", GITHUB_REPOSITORY="wxcvm/AdAway-Windows")
        for summary in (""," ", "# Wrong summary"):
            with self.assertRaises(ValueError): render_notes(summary,"1.90",env)
        for key,value in (("GITHUB_SHA","short"),("GITHUB_RUN_ID","nan"),("GITHUB_REPOSITORY","unexpected/repo")):
            with self.assertRaises(ValueError): render_notes("# Preview changes","1.90",dict(env,**{key:value}))
        with self.assertRaises(ValueError): render_notes("# Preview changes","1.90\nforged",env)

if __name__=="__main__": unittest.main()
