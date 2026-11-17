import copy
import unittest

from verify_native_artifacts import REQUIRED_ARTIFACTS, verify


class NativeArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.commit = "a" * 40
        self.run = {
            "id": 123,
            "head_sha": self.commit,
            "path": ".github/workflows/build.yml",
            "head_branch": "main",
            "event": "push",
            "status": "completed",
            "conclusion": "success",
        }
        self.artifacts = {"artifacts": [
            {
                "name": name,
                "expired": False,
                "size_in_bytes": 100,
                "workflow_run": {"id": 123, "head_sha": self.commit},
            }
            for name in REQUIRED_ARTIFACTS
        ]}

    def test_accepts_all_platforms_from_the_approved_build(self):
        verify(self.run, self.artifacts, "123", self.commit)

    def test_rejects_another_commit_or_run(self):
        for field, value in [("head_sha", "b" * 40), ("id", 456)]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                verify(dict(self.run, **{field: value}), self.artifacts, "123", self.commit)

    def test_rejects_unfinished_failed_or_unapproved_workflows(self):
        for field, value in [
            ("status", "in_progress"),
            ("conclusion", "failure"),
            ("head_branch", "other"),
            ("path", ".github/workflows/other.yml"),
            ("event", "pull_request"),
        ]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                verify(dict(self.run, **{field: value}), self.artifacts, "123", self.commit)

    def test_rejects_missing_duplicate_expired_or_empty_artifacts(self):
        variants = []
        missing = copy.deepcopy(self.artifacts)
        missing["artifacts"].pop()
        variants.append(missing)
        duplicate = copy.deepcopy(self.artifacts)
        duplicate["artifacts"].append(copy.deepcopy(duplicate["artifacts"][0]))
        variants.append(duplicate)
        for field, value in [("expired", True), ("size_in_bytes", 0)]:
            invalid = copy.deepcopy(self.artifacts)
            invalid["artifacts"][0][field] = value
            variants.append(invalid)
        for index, artifacts in enumerate(variants):
            with self.subTest(index=index), self.assertRaises(ValueError):
                verify(self.run, artifacts, "123", self.commit)

    def test_rejects_artifact_provenance_mismatch(self):
        for provenance in [{}, {"id": 456, "head_sha": self.commit}, {"id": 123, "head_sha": "b" * 40}]:
            artifacts = copy.deepcopy(self.artifacts)
            artifacts["artifacts"][0]["workflow_run"] = provenance
            with self.subTest(provenance=provenance), self.assertRaises(ValueError):
                verify(self.run, artifacts, "123", self.commit)

    def test_requires_full_commit_and_numeric_run_id(self):
        for run_id, commit in [("123; echo bad", self.commit), ("0", self.commit), ("123", "abc123")]:
            with self.subTest(run_id=run_id, commit=commit), self.assertRaises(ValueError):
                verify(self.run, self.artifacts, run_id, commit)


if __name__ == "__main__":
    unittest.main()
