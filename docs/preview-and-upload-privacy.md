# Preview and upload privacy

Settings → Media → **Load previews through Tor (Orbot)** sends image previews and
avatars through SOCKS5 at `127.0.0.1:9050`, independently of the IRC network proxy.
Start Orbot in the same Android profile as HexDroid. Unavailable Orbot means failure,
not direct fallback. YouTube thumbnails and playback retain their direct exception.
Server icons are suppressed while this option is enabled rather than fetched directly.

With image previews enabled, HTTP/HTTPS `.onion` links show the same **Load preview**
button as ordinary images. Tapping it loads through Orbot even when the general
Tor-preview switch is off; no onion preview request starts before that tap.
The Wi-Fi-only setting still applies. Image responses render inline; HTML responses show a bounded page title.
HTML is never executed, and page scripts, styles and embedded resources are not loaded.
Encrypted `.age` uploads cannot be previewed before decryption.

Upload routing is separate: the attachment menu's **Upload via Tor** switch sends
drop.fo uploads to its onion service through Orbot. Otherwise uploads use the current
network profile's SOCKS configuration, including changes saved since connection.

Native and script attachments use the same uploader policy: explicit enablement,
selected host, local image metadata removal and optional age encryption. Upload input
and prepared images are limited to 64 MiB. Images over 16 megapixels are refused.
Staging checks free space and cancellation. Custom HTTP upload origins require the
explicit Allow HTTP setting; image and script transports otherwise enforce HTTPS,
except onion HTTP inside Tor. Android's static policy permits cleartext to support
user-defined hosts; the per-request application policy is the enforcement boundary.

Notification operations use a non-exported activity. External launcher and deep-link
intents cannot disconnect, exit or accept transfers through notification extras.

CI runs JVM tests, native age tests and Android instrumentation tests (including image
metadata removal and HTTP consent) as part of the next requested release build.

## Untrusted preview safeguards

Preview requests accept HTTP only for onion services through Orbot and HTTPS for
other hosts. Credentials in URLs, non-web ports, local hostnames, private/reserved IP
literals and invalid-length onion addresses are rejected. Direct DNS answers are checked
before connections; private/reserved answers are rejected, including mixed public/private
answers. SOCKS destinations are resolved remotely to avoid DNS leaks: a generic SOCKS
proxy's remote DNS/private-address policy cannot be verified by this client and must be
configured on the proxy. Upload destinations have their separate, explicit user policy.

Preview redirects are disabled. Four preview requests may run at once across image,
avatar and metadata loaders. Off-screen cancellations close their sockets; inline
previews stop fetching when the activity stops. Shared avatar requests are cancelled
when their last consumer leaves. Downloads have timeouts, a 5 MiB image cap, a 256 KiB
metadata cap and a 64 KiB HTML-title prefix cap. Pixel dimensions are limited to 16 MP
before decoding, with rendered image dimensions sampled down. No page scripts or
subresources execute. YouTube/X recognition checks the actual hostname, so a deceptive
URL containing a service name does not inherit its loading exception.

Automatic previews still contact the destination and may disclose that a link was
viewed; Tor changes the network route, not that fact. The master preview switch and
Wi-Fi-only preference continue to apply. YouTube remains the user's direct exception.
