# DroidUse ROM system API

This module is the typed Binder contract between the updateable Executor APK and
the ROM-owned `DroidUseManagerService`. It is deliberately separate from the
Assistant-facing `IExecutor` protocol.

- The ROM service owns caller authentication, session epochs, target identity,
  resource leases and cleanup.
- Operations are a closed set selected by `domain` and exactly one typed payload.
- Screenshot and audio bytes travel through `ParcelFileDescriptor`; Binder carries
  only control messages and bounded metadata.
- `userId` is present from version 1. The first implementation accepts only user 0.
- Existing fields and transaction order are append-only after the first ROM release.
- `coreReady` must be derived from installed system hooks. A client must also inspect
  individual capability states before requesting an optional operation.

ROM M4 appends call snapshots, explicit call-audio opt-in and call-bound stream observations.
`IDroidUseTelecom` is a separate, SYSTEM_UID-only internal bridge to the platform-signed
ROM companion, not an Assistant API. PCM transport 1 uses `CallPcmFrame` headers;
source availability and private-microphone restrictions are documented in
[the M4 implementation record](../../docs/m4-rom-call-backend.md).

The Gradle module validates the contract on macOS. `Android.bp` is the source module
for the LineageOS tree; it remains unverified until built against the locked ROM checkout.
