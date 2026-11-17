# NFC QR Test App (Tap2Pay bank-app test)

A small Android app that plays **the customer's bank app**, to check that a phone with a bank app installed
opens on a Tap2Pay tap, even when the app is closed, and gets the right data out of the POS's QR. It is not a POS and it
cannot pay: it shows what a bank app would use to send the payment.

It answers the POS exactly like the NBP app (`com.paysys.nbpdigital`, `Tap2PayHceService` +
`Tap2PayLaunchActivity`): same AID `F0010203030605` (category `other`, `requireDeviceUnlock=false`), same
`PushAssembler` header forms (Urovo spec, Sunmi index), same acceptance rule (starts `000201`, ends `6304` + matching
CRC, else `6A80`), same trampoline that asks for unlock on a locked phone. The QR fields come from the mapping in the NBP
app's `LoginQrpayment.js` (`qrDetails`). The look is the NBP app's: its colours (`App/Theme/Colors.js`), the Inter font,
white cards on a light grey page under a blue header.

| Part | Where | What |
|---|---|---|
| `BankResponder` | `core` | The HCE logic of the NBP service, no Android. |
| `BankQr` | `core` | The fields, the "can a bank app send this" checks and the unfolded tag list. |
| `NfcSession`, `NfcReport` | `core` | Every APDU of a tap (command, answer, time) kept as text, and the report that compares each one with what the Tap2Pay spec expects. |
| `QrReport` | `core` | The QR's tags against the tags the bank app and the spec expect. |
| `TapLogText` | `core` | The log of one tap, or of all of them, as plain text for the copy buttons. |
| `TapHistory`, `UnlockRetry` | `core` | The saved list of taps (v2, with each tap's APDUs), which one is still waiting to be shown, the unlock-retry rule. |
| `BankHceService`, `TapLaunchActivity`, `BankStore`, `MainActivity` | `bank-app` | Android glue and the two screens. |

## Screens

**Home**: NFC status (off / on and routed to this app / on but routed elsewhere), a warning that lists any other
installed app whose HCE service registers the same AID (read from their service XML, nothing hard-coded), and the
recent taps (30 kept, newest first, RECEIVED, REJECTED or INCOMPLETE). Tap a row to open it.

**Tap details** (opens by itself on an accepted tap): a status strip with three chips (1 NFC, 2 QR, 3 DATA, each OK /
warning / problem) and three numbered sections. All three start minimized, whatever the result: each header shows
its one-line result, and a tap on a chip or a header opens or closes that section.

1. **NFC communication**: one card per APDU the POS sent (SELECT, then each PUSH INVOICE chunk). Every field (CLA,
   INS, P1, P2, Lc, data, Le) and the phone's answer is shown as *Expected* (what the spec says the POS should send
   and the phone answer) against *Got*, with the raw hex of both. A session card adds: SELECT first, QR delivered,
   header form, gap between APDUs against the spec's 2 s, how the session ended. Where the NBP app answers differently
   from the spec (Sunmi index header, Lc 0, over 4096 bytes) it is a warning, not a failure.
2. **QR validation**: the tags the QR should have against the tags it had, in groups: *Does the phone accept it?*
   (starts `000201`, ends `6304` + the right CRC, every tag parses; this decides `9000` or `6A80`), *Tags the bank app
   reads*, *Tap2Pay spec invoice tags* (26, 81, 82, never required) and *Other tags in the QR* (ignored). Each row
   shows the tag, Expected (required / optional and the format) and Got. Raw QR with *Copy QR* and *Show all tags*.
3. **Data extracted**: merchant card, amount card (total to debit, QR type), the checklist "Can a bank app send this
   payment?" (QR accepted, payee IBAN `28.02`, bank BIC `28.01`, merchant name `59`, currency `53`, amount `54`,
   dynamic reference `62.05` / `84.01`), and the fields in groups (Pay to, Payee account, Amount, References) with
   the QR tag of each.

**Copy log** (under the chips) puts the whole tap on the clipboard as plain text: the three sections in order, every
APDU as hex, the raw QR and the extracted data. **Copy all logs** on the home screen does the same for every saved
tap, newest first (`TapLogText` in `core`).

A rejected tap shows the reason above the chips (open section 2 for the failing tag); section 3 is empty. An incomplete tap (the session ended
with no QR, a torn tap or a POS that sent only SELECT) is kept for the NFC log only: the app does not open for it,
and section 1 shows where the conversation stopped.

It never makes itself the preferred service for the AID (the test-kit phone app does), so the routing line is what a
closed bank app really gets.

## Build and install

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :core:test :bank-app:assembleDebug :bank-app:lintDebug
adb -s <phone-serial> install -r bank-app/build/outputs/apk/debug/bank-app-debug.apk
```

Uninstall or disable the NBP app, the Tapsys Tap2Pay Test Kit (`phone-test`) and `wallet-hce` on the test phone first:
they register the same AID, and with two services Android may route the tap to the other one. The home screen warns when
one is installed. Logcat tag `Tap2PayBank`. Use the Urovo FBL app, the Sunmi POS or the POC `pos-reader` as the POS.

## Scenario matrix

Rows 1-39 and 59-113 have a unit test in `core` (`BankResponderTest`, `BankQrTest`, `TapHistoryTest`,
`NfcReportTest`, `QrReportTest`, `TapLogTextTest`; the row number is in the test's comment); rows 40-58 need a phone.

### POS side (`BankResponder`)

| # | Situation | Expected |
|---|---|---|
| 1 | SELECT | FCI + `9000` |
| 2 | Valid QR, spec header, one APDU | `9000`, accepted once |
| 3 | Valid QR, spec header, chunked | `9000` per chunk; accepted only after the last |
| 4 | Index header (Sunmi), one APDU and chunked | accepted |
| 5 | Bad CRC | `6A80`, rejected with "CRC x should be y", not accepted |
| 6 | Not a Raast QR | `6A80`, rejected "does not start with 000201" |
| 7 | PUSH before SELECT | `6985`, nothing recorded |
| 8 | Wrong CLA / INS | `6E00` / `6D00` |
| 9 | APDU under 4 bytes / null; no SELECT with a wrong class | `6A80`; `6985` (as the NBP service orders the checks) |
| 10 | Chunk out of sequence | `6A86`, buffer dropped, next transfer works |
| 11 | Link lost mid-transfer | buffer dropped, PUSH needs a new SELECT |
| 12 | Second SELECT mid-transfer | starts again, only the new QR is accepted |
| 13 | Same QR twice | two taps |
| 14 | Second transfer without a new SELECT | accepted, as the NBP service allows |
| 15 | Bookkeeping throws | the answer does not change |
| 16 | Payload over 4096 bytes | `6A80`, nothing recorded |
| 17 | UTF-8 merchant name | arrives intact |

### QR fields (`BankQr`)

| # | Situation | Expected |
|---|---|---|
| 18 | Dynamic QR | every NBP `qrDetails` field read; all checks OK |
| 19 | Static QR, no amount | no total, "payer enters the amount", amount check WARN |
| 20 | Tip / fee `55` = 01 / 02 / 03, or the fee tag missing | total as `LoginQrpayment.js` computes it; "plus a tip the payer enters"; no total when the fee tag is missing |
| 21 | `54` is `0`, `0.00`, text, negative | FAIL "cannot be paid", no total |
| 22 | Payee in template 26 only | FAIL, as the NBP app only reads template 28; template 28 without `02` says which sub-tag is missing; non-PK IBAN WARN |
| 23 | Currency not 586 / absent | WARN / FAIL |
| 24 | Dynamic QR with neither `62.05` nor `84.01` | WARN |
| 25 | All template-62 sub-tags (01-11, 50, 51) and tag 80 | each read |
| 26 | QR the NBP app refuses; null, empty, junk | reason kept; screens still build, nothing throws |
| 27 | Duplicate tag | first value, as the NBP app |
| 28 | Raw tag view | every character accounted for; a non-TLV value in a template range stays a leaf; an unreadable tail is shown |
| 29 | Money formatting | `1,234,567.50` |

### Saved taps (`TapHistory`, `UnlockRetry`)

| # | Situation | Expected |
|---|---|---|
| 30 | Several taps | newest first, ids rise |
| 31 | More than 30 | oldest dropped |
| 32 | Accepted tap not yet shown | pending until a screen shows it; a rejected tap is never pending |
| 33 | Older than 5 minutes (the NBP app's QR lifetime); clock set back | not pending |
| 34 | Two taps while locked; rejected tap after an accepted one; an older tap marked shown later | newest unshown accepted tap is pending; showing it retires the older ones; never un-shown |
| 35 | Clear | list empty, ids keep counting, next tap pending |
| 36 | Process killed after the tap | list, pending and shown state survive a round trip (tabs, newlines, UTF-8 included) |
| 37 | Damaged store | empty history or the bad line skipped, never an exception; ids above the counter dropped; over 30 lines trimmed |
| 38 | Unlock request cancelled | retried once only if under 1 s, still locked and not retried |
| 39 | Store written by the first version (v1, no APDUs) | ignored, history starts empty, never mis-read |

### Tap log (`BankResponder` session recording)

| # | Situation | Expected |
|---|---|---|
| 59 | Failure inside the handler | answered `6F00` and still logged |
| 60 | Accepted tap | log holds SELECT and every PUSH with its answer, final `9000` included |
| 61 | Refused QR | log holds the `6A80` that was sent |
| 62 | PUSH before SELECT | reported incomplete when the link drops, `6985` in the log |
| 63 | SELECT only | reported incomplete |
| 64 | Refused chunk, then a good retry in the same field | one accepted tap whose log shows both attempts |
| 65 | New SELECT while a transfer is open | previous session reported incomplete ("new SELECT"); the new one starts at its own SELECT |
| 66 | Accepted or rejected tap, then the link drops | nothing more reported, no incomplete |
| 67 | Two transfers in one session | two taps, each with the log up to its own PUSH |
| 68 | Listener throws | log intact, answer unchanged |
| 69 | Nothing open when the link drops | nothing reported |

### NFC report (`NfcReport`)

| # | Situation | Expected |
|---|---|---|
| 70 | Spec tap, one PUSH | every field OK |
| 71 | Chunked | chunks numbered, last one marked |
| 72 | Sunmi index header | WARN: the spec would answer `6A86` |
| 73 | SELECT without Le | WARN |
| 74 | Wrong SELECT answer | FAIL |
| 75 | Wrong CLA / INS | that field FAILs, `6E00` / `6D00` is the expected refusal |
| 76 | PUSH before SELECT | session FAIL, summary names it |
| 77 | P1 not supported | FAIL, `6A86` |
| 78 | Chunk out of sequence | P2 FAIL |
| 79 | Lc 0 | Lc FAIL, WARN that the spec says `6700` |
| 80 | Lc above 239 | WARN, not FAIL |
| 81 | Data shorter than Lc | FAIL |
| 82 | Bad CRC in the QR | NFC still OK (`6A80` is the spec answer); the QR section carries the problem |
| 83 | Transfer stops half way | FAIL, summary names it |
| 84 | Gap over 2 s between APDUs | WARN |
| 85 | SELECT and nothing else | FAIL |
| 86 | Payload over 4096 bytes | WARN: NBP `6A80`, spec `6A84` |
| 87 | Tap without a log (saved before this version) | no report |
| 88 | Empty session | FAIL, no exception |
| 89 | Session saved and read back; damaged text | identical; damaged text refused |
| 106 | Arrays inside a session | copies, cannot be changed from outside |

### QR report (`QrReport`)

| # | Situation | Expected |
|---|---|---|
| 90 | Well-formed dynamic QR | no problem, no warning |
| 91 | Required tag missing (28, 28.01, 28.02, 52, 53, 58, 59) | FAIL per tag, got "absent"; 00 and 63 stay OK |
| 92 | Amount | dynamic QR without it FAILs ("a dynamic QR needs it"); a static QR may leave it out |
| 93 | Amount format | 2 decimals WARN; zero / text FAIL |
| 94 | `55` = 02 / 03 | FAIL without `56` / `57`, OK with it; 01 OK; unknown value or a non-numeric `56` WARN |
| 95 | Name and city length | EMV limit WARN, looser spec limit noted |
| 96 | Spec-only tags 26, 81, 82 | shown apart, never required |
| 97 | Unknown tag | listed as ignored |
| 98 | Bad CRC or wrong start | acceptance group FAILs, shows the CRC it should have |
| 99 | IBAN | shape and mod-97 checked |
| 100 | Absent optional tags | counted, not listed |
| 101 | Tail that does not parse | WARN in the acceptance group |
| 102 | Dynamic QR with no `62.05` / `84.01` | WARN |
| 103 | Template that is not TLV inside | FAIL, its sub-tags missing |
| 104 | Currency, country | judged |
| 105 | `00` and `01` values | checked |

### Copy log (`TapLogText`)

| # | Situation | Expected |
|---|---|---|
| 107 | Accepted tap | header, sections 1-2-3 in order, every APDU as hex, raw QR, merchant name |
| 108 | Rejected tap | `[FAIL]` on the QR section with the CRC it got, raw payload, no extracted data |
| 109 | Incomplete tap | "no QR reached the phone", NFC section FAIL, no extracted data |
| 110 | Tap saved without an NFC log; empty payload | still written, says no NFC log was saved, no exception |
| 111 | All taps | count line, newest first as given, a rule before each tap; 0 and 1 tap worded right |
| 112 | Time zone | times written in the zone asked for |
| 113 | Any kind of tap, Sunmi header, Lc 0 | no log text names another app (the POS's own QR text is left out of the check) |

### On the phone (device tests)

| # | Situation | Expected |
|---|---|---|
| 40 | App closed (swiped away), phone unlocked, tap | app opens on the details of that tap |
| 41 | App open on the home screen, tap | details of the tap |
| 42 | App open on tap A's details, tap B | B's details; A is in the list |
| 43 | App in the background | comes to the front on the details |
| 44 | Phone locked | unlock prompt over the lock screen, then the details. Cancel the prompt: app does not open, the tap is in the list |
| 45 | Screen off | most phones turn NFC off: no tap expected |
| 46 | Process killed, then app opened | the list is back; a tap whose unlock was cancelled shows its details if under 5 minutes old |
| 47 | Rotate | same screen (the activity handles the change) |
| 48 | Back | details to home; home leaves the app |
| 49 | NBP app, test kit or `wallet-hce` also installed | home shows "Same AID also registered by ..."; the tap may go to the other app |
| 50 | NFC off | red status line |
| 51 | Android refuses to start the app from the service | tap is in the list (logcat "could not open the app"), opens next time the app is in front |
| 52 | Clear history | list empty |
| 53 | POS sends an invalid QR (test kit *Invalid QR* style, bad CRC) | POS gets `6A80`; the app does not open; list shows REJECTED with the reason |
| 54 | Good tap, open its details | all three sections start minimized, chips and one-line results visible; opening section 1 shows SELECT + each PUSH, all Expected = Got, phone answers `9000`; the APDUs match what the POS logged |
| 55 | Pull the phone away after SELECT (before any PUSH) | list shows INCOMPLETE; app does not open; section 1 shows the SELECT and "the POS sent no PUSH" |
| 56 | POS sends a QR with a wrong tag or field | tap still accepted if the CRC is right; section 2 marks the tag, section 1 stays OK |
| 57 | Tap with the Sunmi POS (index header) | section 1 shows WARN "spec would answer `6A86`", tap accepted |
| 58 | *Copy log* on a tap, *Copy all logs* on home, paste into a chat | the text matches the screen; a toast says it was copied |

What a device test proves: 40, 43, 44 and 51 are the only rows that depend on Android's background-start and keyguard
rules, which no unit test can reach; 41, 42, 46-48 and 52 check the screens; 49 and 50 check the status card. 54-57
check that what a real POS sends is logged and judged the way the unit tests expect (the log is recorded by the same
`BankResponder` the tests drive, but the APDU bytes and timing of a real reader are only seen on a phone). The protocol
rows (1-17), the field rows (18-29), the tap-log rows (59-69) the report rows (70-106) and the copy-log rows (107-113) are already covered.
