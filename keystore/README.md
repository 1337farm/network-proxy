# Demo signing key (NOT a production identity)

`network-proxy-demo.keystore` is a committed, publicly-known demo key so that
every CI build signs with the same certificate and installs as an **update**
over the previous APK (ephemeral debug keys change per runner and force an
uninstall first).

- Alias `demo`, passwords `networkproxy` (see `app/build.gradle.kts`).
- Never use this key for anything but the demo app.
