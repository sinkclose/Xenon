# Push pipeline audit

Reference: [DrKLO/Telegram, commit 3de37c8cfb039452be7398909461da6288babf15](https://github.com/DrKLO/Telegram/tree/3de37c8cfb039452be7398909461da6288babf15).
The comparison used source at that commit, not a moving branch.

## Reference behavior retained

- Firebase token retrieval, token refresh, and `account.registerDevice` follow
  the official client. Registration errors do not automatically enable a
  persistent service or change background-connection preferences.
- The official encrypted `p` payload has priority over Xenon's `n` remote
  actions. Application initialization precedes decoding and registration.
- Payload authentication, account selection, notification dispatch, and
  partial network resume retain the official processing logic.
- Keep-alive service defaults still come from the user setting and server
  `keep_alive_service` configuration. FCM availability is not used as proof
  that a token was accepted by Telegram's servers.
- Native `Connection.cpp`, the network sleep/resume loop, and push-ping
  intervals match the reference. Socket shutdown removes the descriptor from
  epoll and calls `close()`; this audit did not establish a descriptor leak.
- Manifest FCM service/permission declarations match the reference. The app
  module applies the Google Services plugin. Existing release CI requires a
  Firebase configuration matching the app package; PR placeholder builds do
  not provide working FCM credentials.

## Corrections relative to the reference

Some defects below also exist in the reference. These are deliberate changes,
not claims that the official repository already contains the fixes.

- Push-connection policy reads the current account's preferences and server
  configuration, matching the account-specific notification settings UI.
  It no longer reads account 0's setting for every account. The separate
  process-wide keep-alive service switch reads and writes the global setting
  consumed by `ApplicationLoader`, including when edited from another account.
- Optimized mode supplies a temporary background-connection default and stops
  the keep-alive service. An explicit per-account user choice takes priority.
  Toggling the mode refreshes active accounts without persisting a forced
  `pushConnection=true`. Turning it off restores each account's effective
  policy. Starting the application does not initialize transports merely to
  stop the service. Previously overwritten preferences cannot be distinguished
  from intentional user choices and are preserved.
- Missing or empty `p` payloads are ignored instead of entering decryption-error
  recovery and waking every active account. Xenon remote actions still work;
  actual encrypted payload failures retain official recovery behavior.
- Every push gets its own completion latch. A previous notification cannot
  make the Firebase callback return before the next notification is processed.
  Decryption-error recovery signals the latch belonging to that push.
- A failed keep-alive job enqueue or interrupted worker clears its pending
  flag so later pushes can retry. Successful pending jobs still suppress
  duplicates.
- Native push enable/disable operations run on the network queue. Reapplying
  the same value does not send another ping. Sending a push ping updates the
  timestamp used by its timeout, and disabled push connections reject pings.

## Validation and limits

`python3 -m unittest discover -s tests -p 'test_push*.py' -v` requires a JDK
and C++17 compiler (`CXX` may specify a compiler command).

The tests execute production Java and C++ methods with platform stand-ins.
They cover account isolation, explicit settings, reversible optimized mode,
inactive accounts, FCM routing, repeated push completion, keep-alive enqueue
failure, queued socket operations, duplicate enables, and ping timeouts.
The completion test stubs decoding and retains the production dispatch/wait
wrapper; it does not test real encrypted payloads or Android process lifetime.

The Java sources also passed syntax and duplicate-field parsing. An Android
SDK and real Firebase configuration are not installed in this workspace, so
an APK build and device delivery test remain unverified.

The reported 2000 router/VPN connections have not been reproduced. Verify on a
device with several consecutive FCM notifications after backgrounding, two
accounts with different background-connection settings, optimized mode on/off,
and a VPN network handover. Compare application/native connection logs with
router connection states before attributing the accumulated entries to a
specific change.
