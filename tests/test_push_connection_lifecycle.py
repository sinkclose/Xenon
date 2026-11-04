"""Run production tgnet push lifecycle methods against a native test harness."""

import os
import pathlib
import shlex
import shutil
import subprocess
import tempfile
import unittest

from test_glass_blur import method

ROOT = pathlib.Path(__file__).resolve().parents[1]


class PushConnectionLifecycleTest(unittest.TestCase):
    def test_network_queue_and_ping_deadlines(self):
        compiler = shlex.split(os.environ.get("CXX", "c++"))
        if not shutil.which(compiler[0]):
            self.skipTest("C++ compiler required (or set CXX)")
        source = (ROOT / "TMessagesProj/jni/tgnet/ConnectionsManager.cpp").read_text()
        methods = "\n".join(method(source, sig) for sig in (
            "void ConnectionsManager::setPushConnectionEnabled(",
            "void ConnectionsManager::sendPing(",
        ))
        timeout = next(line.strip()[4:-3] for line in source.splitlines()
                       if line.strip().startswith("if ((sendingPushPing &&"))
        harness = r"""
#include <cassert>
#include <cstdint>
#include <cstdlib>
#include <functional>
#include <memory>
#include <queue>
#include <vector>
#define LOGS_ENABLED false
#define DEBUG_D(...) ((void)0)
struct TLObject { virtual ~TLObject() = default; };
struct TL_ping_delay_disconnect : TLObject {
    int64_t ping_id; int disconnect_delay;
    int getObjectSize() { return 12; }
};
struct TL_message {
    int64_t msg_id; int bytes, seqno;
    std::unique_ptr<TLObject> body;
};
struct NetworkMessage { std::unique_ptr<TL_message> message; };
struct NativeByteBuffer {};
struct Connection {
    int sessions = 0, closes = 0, sends = 0;
    void setSessionId(int64_t) { ++sessions; }
    void suspendConnection() { ++closes; }
    int getConnectionToken() { return 1; }
    int generateMessageSeqNo(bool) { return 0; }
    void sendData(NativeByteBuffer*, bool, bool) { ++sends; }
};
struct Datacenter {
    Connection push, generic;
    int creates = 0, pushGets = 0;
    bool exists = false;
    Connection *getPushConnection(bool create) {
        ++pushGets;
        if (create) exists = true;
        return exists ? &push : nullptr;
    }
    Connection *createPushConnection() { ++creates; exists = true; return &push; }
    Connection *getGenericConnection(bool, int) { return &generic; }
    int getDatacenterId() { return 2; }
    NativeByteBuffer *createRequestsData(
        std::vector<std::unique_ptr<NetworkMessage>>&, void*, Connection*, bool
    ) { return nullptr; }
};
struct ConnectionsManager {
    bool pushConnectionEnabled = false, sendingPushPing = false, sendingPing = false;
    bool testBackend = false;
    int currentUserId = 1, currentDatacenterId = 2;
    int64_t pushSessionId = 123, lastPingId = 0, now = 1000000;
    int64_t lastPushPingTime = 0, sendingPushPingTime = 0, pingTimeMs = 0;
    int pingTime = 0, nextPingTimeOffset = 180000;
    Datacenter dc;
    bool hasDc = true;
    std::queue<std::function<void()>> tasks;
    void scheduleTask(std::function<void()> f) { tasks.push(f); }
    void drain() { while (!tasks.empty()) { auto f = tasks.front(); tasks.pop(); f(); } }
    Datacenter *getDatacenterWithId(int) { return hasDc ? &dc : nullptr; }
    int64_t getCurrentTimeMonotonicMillis() { return now; }
    int64_t generateMessageId() { return ++lastPingId; }
    void setPushConnectionEnabled(bool);
    void sendPing(Datacenter*, bool);
    bool timedOut() { return TIMEOUT; }
};
METHODS
int main() {
    ConnectionsManager m;
    m.setPushConnectionEnabled(true);
    assert(!m.pushConnectionEnabled && m.dc.creates == 0 && m.dc.push.sends == 0);
    m.drain();
    assert(m.pushConnectionEnabled && m.dc.creates == 1 && m.dc.push.sends == 1);
    assert(m.lastPushPingTime == m.now && !m.timedOut());
    m.now += 29999;
    assert(!m.timedOut());
    ++m.now;
    assert(m.timedOut());
    for (int i = 0; i < 1000; ++i) m.setPushConnectionEnabled(true);
    m.drain();
    assert(m.dc.creates == 1 && m.dc.push.sends == 1 && m.dc.push.closes == 0);
    m.setPushConnectionEnabled(false);
    assert(m.pushConnectionEnabled && m.dc.push.closes == 0);
    m.drain();
    assert(!m.pushConnectionEnabled && m.dc.push.closes == 1);
    int gets = m.dc.pushGets;
    m.sendPing(&m.dc, true);
    assert(m.dc.pushGets == gets && m.dc.push.sends == 1);
    m.now += 600000;
    m.setPushConnectionEnabled(true);
    m.setPushConnectionEnabled(false);
    m.setPushConnectionEnabled(true);
    m.drain();
    assert(m.dc.push.sends == 3 && m.dc.push.closes == 2 && !m.timedOut());
    // Thread startup sends its first ping without going through the toggle.
    ConnectionsManager startup;
    startup.pushConnectionEnabled = true;
    startup.sendPing(&startup.dc, true);
    assert(startup.lastPushPingTime == startup.now && !startup.timedOut());
    startup.currentUserId = 0;
    startup.sendPing(&startup.dc, true);
    assert(startup.dc.push.sends == 1);
    startup.sendPing(&startup.dc, false);
    assert(startup.dc.generic.sends == 1 && startup.sendingPing);
    ConnectionsManager noDc;
    noDc.hasDc = false;
    noDc.setPushConnectionEnabled(true);
    noDc.drain();
    assert(noDc.pushConnectionEnabled && noDc.dc.creates == 0);
}
""".replace("METHODS", methods).replace("TIMEOUT", timeout)
        with tempfile.TemporaryDirectory() as temp:
            cpp = pathlib.Path(temp) / "push.cpp"
            binary = pathlib.Path(temp) / "push"
            cpp.write_text(harness)
            build = subprocess.run(compiler + ["-std=c++17", str(cpp), "-o", str(binary)],
                                   capture_output=True, text=True)
            self.assertEqual(build.returncode, 0, build.stdout + build.stderr)
            run = subprocess.run([str(binary)], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)


if __name__ == "__main__":
    unittest.main()
