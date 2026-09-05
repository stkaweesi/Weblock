# Website Blocker (Android)

A personal, always-on website blocker. It runs a lightweight local VPN that only
intercepts DNS lookups — when your phone asks "what's the IP for facebook.com?",
if facebook.com is on your list, it answers with a dead address instead of the
real one, so the connection never completes. Everything else on your phone's
internet traffic passes through normally; this doesn't route or see your
general browsing.

## How to build and run

1. Install Android Studio (free, from developer.android.com) if you don't have it.
2. Open this folder in Android Studio ("Open an existing project").
3. If it flags the Gradle wrapper as incomplete, let it repair/regenerate it,
   then let the Gradle sync finish (needs internet the first time, to fetch
   build tools).
4. Enable USB debugging on your phone: Settings → About phone → tap "Build
   number" 7 times → back out to Settings → Developer options → USB debugging.
5. Plug the phone in, click Run (▶) in Android Studio, pick your phone. It
   installs straight to the device.

## Using it

- Type a domain (e.g. `facebook.com`) and tap Add — this also blocks
  subdomains like `m.facebook.com`.
- Flip the switch to turn blocking on. Android shows a one-time "Connection
  request" dialog — that's the OS asking you to confirm this app can filter
  network traffic; accept it.
- Long-press an entry to remove it.
- The blocklist is saved on-device, so it survives app restarts and reboots.

## Limitations worth knowing

- Blocking is by domain name, so it's whole-site (can't block just
  `reddit.com/r/news` while allowing the rest of Reddit).
- A browser that hardcodes its own DNS-over-HTTPS resolver instead of using
  your phone's system DNS can occasionally bypass this. If a blocked site
  still loads in one specific browser, turn off "Use secure DNS" in that
  browser's settings.
- IPv4 only in this version — an IPv6-only network isn't covered.
- Because it's a personal-use build (not from the Play Store), Android will
  ask you to re-approve the VPN permission if you ever uninstall/reinstall it.
