"""Exercise the production cursor SQL against SQLite without the Android SDK.

Run with: python3 -m unittest discover -s tests -p 'test_feed*.py' -v
Requires Python 3 and Java 17+ with the jdk.compiler module.
"""

import pathlib
import sqlite3
import subprocess
import tempfile
import unittest


class FeedCursorSqlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = (pathlib.Path(__file__).resolve().parents[1] /
                  "TMessagesProj/src/main/java/org/telegram/messenger/feed/FeedTimelineLoader.java").read_text()
        start = source.index("    private static void appendCursorBound(")
        end = source.index("    private static int compareDesc(", start)
        # Compile the actual production method, supplying only its cursor type.
        harness = """
public class FeedCursorSqlHarness {
    static class Cursor {
        int date = 100, mid = 5;
        long uid = -2;
    }
    public static void main(String[] args) {
        StringBuilder sql = new StringBuilder();
        appendCursorBound(sql, new Cursor(), Boolean.parseBoolean(args[0]),
                          Boolean.parseBoolean(args[1]));
        System.out.print(sql);
    }
""" + source[start:end] + "}\n"
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        path = pathlib.Path(cls.temp.name) / "FeedCursorSqlHarness.java"
        path.write_text(harness)
        subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main",
                        str(path)], check=True, capture_output=True, text=True)
        cls.bounds = {}
        for older in (False, True):
            for inclusive in (False, True):
                result = subprocess.run(
                    ["java", "-cp", cls.temp.name, "FeedCursorSqlHarness",
                     str(older).lower(), str(inclusive).lower()],
                    check=True, capture_output=True, text=True)
                cls.bounds[older, inclusive] = result.stdout

    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.execute("CREATE TABLE messages_v2 (date INTEGER, uid INTEGER, mid INTEGER)")
        self.rows = [(date, uid, mid) for date in (99, 100, 101)
                     for uid in (-3, -2, -1) for mid in (4, 5, 6)]
        self.db.executemany("INSERT INTO messages_v2 VALUES (?, ?, ?)", self.rows)

    def test_older_and_newer_boundaries(self):
        boundary = (100, -2, 5)
        for (older, inclusive), clause in self.bounds.items():
            with self.subTest(older=older, inclusive=inclusive):
                # An additional filter must apply to every branch of the bound.
                sql = ("SELECT date, uid, mid FROM messages_v2 WHERE mid > 0" +
                       clause + " AND uid = -2 ORDER BY date, uid, mid")
                expected = [row for row in self.rows if row[1] == -2 and
                            ((row < boundary if older else row > boundary) or
                             (inclusive and row == boundary))]
                self.assertEqual(sorted(expected), self.db.execute(sql).fetchall())

    def test_channel_window_combines_two_bounds(self):
        sql = ("SELECT date, uid, mid FROM messages_v2 WHERE uid = -2" +
               self.bounds[True, True] + self.bounds[False, True] +
               " ORDER BY date DESC, uid DESC, mid DESC LIMIT 501")
        self.assertEqual([(100, -2, 5)], self.db.execute(sql).fetchall())


if __name__ == "__main__":
    unittest.main()
