# Android boot listener recovery

Device observation on 2026-10-20: Android 17/API 37, installed preview.20.
The persistent boot log records the custom ULA listener failing with bind errno 99
(address unavailable). The app subsequently records a foreground boot-service timeout.
Later manual startup has both configured IPv4/IPv6 HTTP and HTTPS sockets listening.
The on-device boot script was rewritten after the failed boot; its current contents
cannot establish which address preparation commands ran during that earlier boot.

The generated boot script now derives private aliases from the selected addresses
when automatic creation is enabled, even before a previous successful import has
populated address ownership. Existing aliases remain untouched; 127/8 needs no alias.
Only lo host aliases are created, with IPv6 nodad. No default routes or DNS are changed.
An address creation failure is retained in the boot log.

Server readiness now waits up to 15 seconds using a monotonic clock instead of five
short polls. Every configured HTTP/HTTPS endpoint must accept a TCP connection.
This establishes listening, not client certificate trust or successful HTTPS content.
Concurrent app boot fallbacks recheck under the listener lock and reuse a ready
instance. Explicit restart continues to restart. The foreground boot service performs
network checks off the main thread and has one bounded polling chain.

Tests cover generated boot-script address-before-launch ordering, opt-out, alias
selection boundaries, a real listener arriving after the previous one-second budget,
timeout/cancellation, and delayed IPv4/IPv6 loopback listeners on API 30/35 emulators.
No package installation, reboot, address mutation or service restart was performed on
the user's phone. Acceptance after a real boot of the new preview remains separate.
