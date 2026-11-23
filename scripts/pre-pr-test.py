#!/usr/bin/env python3
"""Unit tests for scripts/pre-pr."""

import importlib.machinery
import importlib.util
import os
import subprocess
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PRE_PR = os.path.join(ROOT, "scripts", "pre-pr")

loader = importlib.machinery.SourceFileLoader("season_pre_pr_mod", PRE_PR)
spec = importlib.util.spec_from_loader(loader.name, loader)
pre_pr = importlib.util.module_from_spec(spec)
loader.exec_module(pre_pr)


def git(cwd, *args):
    res = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, check=True)
    return res.stdout.strip()


class PrePrTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = self.tmp.name
        git(self.repo, "init", "-q", "-b", "main")
        git(self.repo, "config", "user.name", "Test")
        git(self.repo, "config", "user.email", "test@example.invalid")
        os.makedirs(os.path.join(self.repo, ".githooks"), exist_ok=True)
        with open(os.path.join(ROOT, ".githooks", "commit-msg"), encoding="utf-8") as src:
            with open(os.path.join(self.repo, ".githooks", "commit-msg"), "w", encoding="utf-8") as dst:
                dst.write(src.read())
        os.makedirs(os.path.join(self.repo, pre_pr.MIGRATION_DIR), exist_ok=True)
        with open(os.path.join(self.repo, pre_pr.MIGRATION_DIR, "V1__init.sql"), "w", encoding="utf-8") as f:
            f.write("CREATE TABLE t (id INT);\n")
        git(self.repo, "add", ".")
        git(self.repo, "commit", "-q", "-m", "chore: initial commit")
        git(self.repo, "checkout", "-q", "-b", "nt/test")

    def tearDown(self):
        self.tmp.cleanup()

    def test_valid_commit_title_and_body_pass(self):
        with open(os.path.join(self.repo, "README.md"), "w", encoding="utf-8") as f:
            f.write("hello\n")
        git(self.repo, "add", "README.md")
        git(self.repo, "commit", "-q", "-m", "docs: add readme")
        body = os.path.join(self.repo, "body.md")
        with open(body, "w", encoding="utf-8") as f:
            f.write("Adds a readme.\n")
        status, _, _ = pre_pr.check_commits_and_pr(self.repo, "main", "docs: add readme", body)
        self.assertEqual(status, "PASS")

    def test_bad_pr_title_and_body_fail(self):
        body = os.path.join(self.repo, "body.md")
        tracker_id = "NT" + "2-999"
        with open(body, "w", encoding="utf-8") as f:
            f.write(f"Implements {tracker_id}.\n")
        status, _, detail = pre_pr.check_commits_and_pr(self.repo, "main", "Bad title.", body)
        self.assertEqual(status, "FAIL")
        self.assertIn("pr-title:", detail)
        self.assertIn("pr-body:", detail)

    def test_editing_existing_migration_fails(self):
        mig = os.path.join(self.repo, pre_pr.MIGRATION_DIR, "V1__init.sql")
        with open(mig, "a", encoding="utf-8") as f:
            f.write("ALTER TABLE t ADD COLUMN x INT;\n")
        diff_base = pre_pr.resolve_merge_base(self.repo, "main")
        status, _, detail = pre_pr.check_migrations(self.repo, diff_base)
        self.assertEqual(status, "FAIL")
        self.assertIn("Flyway forbids checksum edits", detail)

    def test_base_advancing_with_new_migration_does_not_fail(self):
        git(self.repo, "checkout", "-q", "main")
        with open(os.path.join(self.repo, pre_pr.MIGRATION_DIR, "V2__upstream.sql"), "w", encoding="utf-8") as f:
            f.write("CREATE TABLE u (id INT);\n")
        with open(os.path.join(self.repo, "UPSTREAM.md"), "w", encoding="utf-8") as f:
            f.write("upstream\n")
        git(self.repo, "add", ".")
        git(self.repo, "commit", "-q", "-m", "feat: upstream migration")
        git(self.repo, "checkout", "-q", "nt/test")
        with open(os.path.join(self.repo, "LOCAL.md"), "w", encoding="utf-8") as f:
            f.write("local\n")
        git(self.repo, "add", "LOCAL.md")
        git(self.repo, "commit", "-q", "-m", "docs: local change")

        diff_base = pre_pr.resolve_merge_base(self.repo, "main")
        self.assertEqual(pre_pr.changed_files(self.repo, diff_base), ["LOCAL.md"])
        status, _, detail = pre_pr.check_migrations(self.repo, diff_base)
        self.assertEqual(status, "PASS", detail)

    def test_valid_new_migration_passes_and_duplicate_version_fails(self):
        diff_base = pre_pr.resolve_merge_base(self.repo, "main")
        good = os.path.join(self.repo, pre_pr.MIGRATION_DIR, "V2__add_col.sql")
        with open(good, "w", encoding="utf-8") as f:
            f.write("ALTER TABLE t ADD COLUMN x INT;\n")
        status, _, _ = pre_pr.check_migrations(self.repo, diff_base)
        self.assertEqual(status, "PASS")

        dup = os.path.join(self.repo, pre_pr.MIGRATION_DIR, "V2__another_col.sql")
        with open(dup, "w", encoding="utf-8") as f:
            f.write("ALTER TABLE t ADD COLUMN y INT;\n")
        status, _, detail = pre_pr.check_migrations(self.repo, diff_base)
        self.assertEqual(status, "FAIL")
        self.assertIn("duplicate migration version V2", detail)

    def test_gradle_task_planning_targets_changed_modules_and_api_types(self):
        with open(os.path.join(self.repo, "settings.gradle.kts"), "w", encoding="utf-8") as f:
            f.write('include("smp")\ninclude("steward")\ninclude("resource-pack")\n')
        for mod, plugin in (
            ("smp", 'id("nordtal.paper-plugin")'),
            ("steward", 'id("nordtal.jvm-app")'),
            ("resource-pack", 'id("base")'),
        ):
            os.makedirs(os.path.join(self.repo, mod, "src", "main", "java"), exist_ok=True)
            with open(os.path.join(self.repo, mod, "build.gradle.kts"), "w", encoding="utf-8") as f:
                f.write(f"plugins {{ {plugin} }}\n")
        os.makedirs(os.path.join(self.repo, "smp", "src", "test", "java"), exist_ok=True)

        files = [
            "smp/src/main/java/Foo.java",
            "steward/src/main/java/Bar.java",
            "resource-pack/src/pack.mcmeta",
            "build.gradle.kts",
        ]
        tasks = pre_pr.plan_gradle_tasks(self.repo, files, fix=False)
        self.assertEqual(
            tasks,
            [
                ":spotlessCheck",
                ":smp:spotlessCheck",
                ":smp:checkstyleMain",
                ":smp:checkstyleTest",
                ":steward:spotlessCheck",
                ":steward:checkstyleMain",
                ":steward:test",
                "--tests",
                "*ApiTypesTest",
                "--tests",
                "*BrandCssTest",
                "--tests",
                "*TextTypesTest",
                "--tests",
                "*TextPagesTest",
            ],
        )

        fix_tasks = pre_pr.plan_gradle_tasks(self.repo, ["steward/src/main/java/Bar.java"], fix=True)
        self.assertEqual(
            fix_tasks,
            [":steward:spotlessApply", ":steward:checkstyleMain", ":steward:generateApiTypes"],
        )


if __name__ == "__main__":
    unittest.main()
