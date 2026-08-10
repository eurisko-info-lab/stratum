# Stratum Studio for Android

The Android client is generic: it contains no application language, keywords,
layout, diagnostics, or commands. It asks a remote Stratum world for the same
language service and profile data consumed by other Studio clients.

## Run the service

Run the service from a checkout that contains the application world. The
listener binds to loopback unless another address is explicitly requested.

```bash
sbt "runMain stratum.remote.RemoteServer --world <world> --host 0.0.0.0 --port 2087"
```

Use `127.0.0.1` when the client reaches the service through a tunnel. Binding
to `0.0.0.0` exposes an unauthenticated, unencrypted development protocol and
is appropriate only on a trusted network.

The Android emulator reaches the development machine as `10.0.2.2`. A physical
device can use the machine's LAN address, or an ADB reverse mapping:

```bash
adb reverse tcp:2087 tcp:2087
```

With ADB reverse active, connect the app to `127.0.0.1:2087`.

## Build

Android SDK 35 and JDK 17 or newer are required.

```bash
cd studio/android
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.

## Client surface

- connects to a world over byte-counted LSP framing;
- discovers languages, workflow, catalogue, and profile views from that world;
- opens documents through Android's system picker;
- publishes live edits, saves through the document provider, and displays diagnostics;
- invokes the world's formatter and evaluator;
- adapts navigation for phone and tablet widths.

The cleartext socket is intentionally a development transport. A distributable
deployment should put the service behind an authenticated encrypted tunnel or
replace the transport without changing the world protocol.