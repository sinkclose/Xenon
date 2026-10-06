"""Verify complete installed-to-APK history with production Java methods."""

import subprocess
import tempfile
import unittest
from pathlib import Path

from test_glass_blur import ROOT, method


class UpdateChangelogTest(unittest.TestCase):
    def test_complete_range_and_release_refs(self):
        source = (ROOT / "zxc/iconic/xenon/helpers/remote/GitHubUpdateHelper.java").read_text()
        methods = "\n".join(method(source, signature) for signature in (
            "public static String getReleaseCommit(", "private static String fetchChangelog(",
        )).replace("com.google.gson.", "")
        harness = r'''
import java.util.*;
public class UpdateChangelogHarness {
    static final String POSRAL_TAG_PREFIX = "posral-";
    static final String BASE = "a".repeat(40), HEAD = "b".repeat(40);
    static final String API = "https://api.github.com/repos/sinkclose/Xenon/";
    static class GitHubRelease { String body, tagName; }
    static class JsonElement {
        Object value;
        JsonElement(Object value) { this.value = value; }
        JsonObject getAsJsonObject() { return (JsonObject)this; }
        String getAsString() { return (String)value; }
        int getAsInt() { return (Integer)value; }
    }
    static class JsonObject extends JsonElement {
        Map<String, JsonElement> values = new HashMap<>();
        JsonObject() { super(null); }
        JsonElement get(String key) { return values.get(key); }
        JsonObject getAsJsonObject(String key) { return (JsonObject)get(key); }
        JsonArray getAsJsonArray(String key) { return (JsonArray)get(key); }
        JsonObject put(String key, Object value) {
            values.put(key, value instanceof JsonElement ? (JsonElement)value : new JsonElement(value));
            return this;
        }
    }
    static class JsonArray extends JsonElement implements Iterable<JsonElement> {
        List<JsonElement> values = new ArrayList<>();
        JsonArray() { super(null); }
        public Iterator<JsonElement> iterator() { return values.iterator(); }
    }
    static Map<String, JsonObject> replies = new HashMap<>();
    static List<String> requests = new ArrayList<>();
    static JsonObject fetchJson(String endpoint) throws Exception {
        requests.add(endpoint);
        if (!replies.containsKey(endpoint)) throw new Exception("Unexpected endpoint: " + endpoint);
        return replies.get(endpoint);
    }
    METHODS
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static String endpoint(int page) { return API + "compare/" + BASE + "..." + HEAD + "?per_page=100&page=" + page; }
    static JsonObject page(int first, int last, int total) {
        JsonArray commits = new JsonArray();
        for (int i = first; i <= last; i++) {
            commits.values.add(new JsonObject().put("sha", String.format("%040x", i))
                .put("commit", new JsonObject().put("message", "Change " + i + "\n\nDetails")));
        }
        return new JsonObject().put("total_commits", total).put("status", "ahead").put("commits", commits);
    }
    public static void main(String[] args) throws Exception {
        GitHubRelease release = new GitHubRelease();
        release.tagName = "v12.10.1";
        release.body = "## Build Info\n- **Commit:** `" + HEAD + "`\n- Other metadata";
        check(getReleaseCommit(release).equals(HEAD));
        release.body = null;
        check(getReleaseCommit(release).equals("v12.10.1"));
        release.tagName = "posral-bbbbbbb";
        check(getReleaseCommit(release).equals("bbbbbbb"));
        replies.put(endpoint(1), page(1, 100, 205));
        replies.put(endpoint(2), page(101, 200, 205));
        replies.put(endpoint(3), page(201, 205, 205));
        String text = fetchChangelog(BASE, HEAD);
        check(text.split("\n\n").length == 205);
        check(text.contains("Change 1\n\n") && text.endsWith("Change 205"));
        check(!text.contains("Details"));
        check(requests.size() == 3);
        for (String request : requests) check(request.contains(BASE + "..." + HEAD));
        // Tags resolve to one fixed commit before pagination begins.
        requests.clear();
        replies.put(API + "commits/v12.10.1", new JsonObject().put("sha", HEAD));
        check(fetchChangelog(BASE, "v12.10.1").equals(text));
        check(requests.size() == 4 && requests.get(0).endsWith("commits/v12.10.1"));
        // Empty or repeated pages fail explicitly rather than cache partial history.
        replies.put(endpoint(2), page(1, 100, 205));
        try { fetchChangelog(BASE, HEAD); throw new AssertionError(); }
        catch (Exception expected) { check(expected.getMessage().equals("Incomplete commit history")); }
        replies.put(endpoint(2), page(1, 0, 205));
        try { fetchChangelog(BASE, HEAD); throw new AssertionError(); }
        catch (Exception expected) { check(expected.getMessage().equals("Incomplete commit history")); }
        replies.put(endpoint(1), page(1, 0, 0));
        check(fetchChangelog(BASE, HEAD).equals("No new commits in this build."));
        requests.clear();
        try { fetchChangelog("unknown", HEAD); throw new AssertionError(); }
        catch (Exception expected) { check(requests.isEmpty()); }
    }
}
'''.replace("METHODS", methods)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "UpdateChangelogHarness.java"
            path.write_text(harness)
            compiled = subprocess.run(
                ["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", str(path)],
                capture_output=True, text=True,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run(
                ["java", "-cp", directory, "UpdateChangelogHarness"], capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
