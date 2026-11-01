# Native scouting bridge

This is the source for the import-free Windows x64 runtime image in
`src/main/resources/native/scouting/scouting-bridge.properties`. The application
uses JNA directly; neither Cheat Engine nor a compiler is required at runtime.

Rebuild after changing the C source:

```powershell
uv run --python 3.13 --with capstone python src/main/native/scouting/build_bridge.py --tcc-dir 'C:/Program Files/Cheat Engine'
```

The developer-only generator uses the existing TCC DLL, normalizes source line
endings, compiles twice at different addresses, and checks relocation invariance
and absence of truncated absolute-address stores. The resource includes source
and image SHA-256 values. `ScoutingBridgeImageTest` checks both; Windows tests
execute the shipped image against mocked FM objects and Win32 callbacks.

The native ABI is gated to FM27 `27.0.0+2486695`, module SHA-256
`bc940e472418fc5c214208d7cf30fecf600f689b6f9d71c11ba19a2df0fe057f`.
`ScoutingReportLayout` owns every supplied FM offset. Its validator rejects
layout drift before writes. IDs, manager, employer, team, club and game date are
checked again inside each UI-thread transaction.

Generation calls the native factory; registration calls the native routine
which removes and destroys existing reports for that player before insertion.
The 20/20 option changes raw JCA/JPA to100/100 only during generation and restores
the original two bytes before attachment. FM retains its own native judgement
algorithm, including potential-estimate uncertainty.

Each live FM process owns one reused bridge allocation (about6.7KB RX image plus
4KB RW control). It remains allocated until FM exits: a forwarded, reentrant
WNDPROC frame can still hold an instruction pointer after the one-shot handler
has been restored. Pinning this bounded allocation avoids freeing its code while
such a frame could return. Active callback counts and an owner-thread message
barrier prevent control reuse while forwarded callbacks are active. The original
window procedure is restored before generation begins. Unknown timeout/read
completion poisons the session and prevents further jobs until FM restarts.

Live proof before integration: Abel Bretones UID67288450 displayed Extensive
Knowledge and a populated report by Feyenoord's Micha Jansen on3August2026.
Independent RAM verification confirmed manager/team ownership, 100/5 knowledge,
generated JCA20 and exact restoration of the original scout attributes.

Direct Java/JNA integration subsequently created an unscouted Aaron Ramsdale
report and replaced Abel's existing report in best-club-scout mode (Guido
Schoen), then refreshed both in20/20 mode without increasing duplicate counts.
The original source judgement bytes4242 were restored. The factory's PLAYER
argument also supports PLAYER_STAFF: live Paul Ifill UID109160, Joshua Mullin
UID215468 and James Murphy UID356951 each have PERSON displacement0x380 and
a virtual PERSON delta0x378 from complete+8. The host checks this native virtual
PERSON relationship for both pure players and hybrids before invoking FM.

Recruitment list enrollment additionally creates a native SCOUT_ASSIGNMENT_ENTRY
using its size-specific pool descriptor, initializes its owned report copy, and
passes ownership to the native recruitment context insertion routine. The pool
manager's descriptor table is checked before any report or judgement writes;
passing the manager itself to the freelist allocator is invalid. Regression
fixtures deliberately keep the manager and allocation pool as distinct objects.

In live PID13340, creating Aaron Hickey's report and cancelling before the next
player produced exactly one profile report and one recruitment entry. Repeating
the request replaced both copies without duplicating the entry. FM displayed
Aaron Hickey in Recruitment / Scouting, increasing recommended players7to8,
All8to9 and Players Scouted8to9. Independent reads confirmed both report copies
had JCA20 and100percent knowledge, separate owned extra-data allocations, and
the original scout attributes4242 restored. The native report-copy constructor
resets its cached category byte to0; the canonical profile report remains100/5.

FM's native recruitment feed keeps up to100 entries and evicts the oldest when
adding beyond that capacity. The companion does not change that engine limit;
individual player profile reports remain a separate collection.
