# DP-FLIX TV — TV Bro integration

The DP-FLIX TV shell remains responsible for access control, startup video, home, settings,
replay, player, downloads and the Netlify companion.

For Films & Series on TV, the web experience is delegated to the embedded TV Bro 2.1.6
implementation in `:tvbro` / `:tvbrocommon`.

TV Bro owns the WebView, cursor, D-pad/joystick translation, focus, IME, native address bar,
fullscreen and browser navigation. DP-FLIX launches it with the selected stream URL and a
host allow-list. In locked mode external main-frame navigation and new-tab requests are
rejected, while subresources/CDNs continue to load normally inside the allowed page.

The five stream routes remain selected by DP-FLIX. The browser activity is single-task so a
new stream selection reuses the same TV Bro surface and refreshes the allow-list.
