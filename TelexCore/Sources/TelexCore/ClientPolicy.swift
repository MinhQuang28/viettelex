// ClientPolicy.swift
// Pure, testable classification of client apps by bundle identifier.
//
// Remote-desktop / virtualization / screen-sharing apps forward raw scancodes to a
// guest OS; a synthesized Unicode syllable is meaningless there and comes out wrong.
// For those clients the IME must behave exactly as if it were OFF.
//
// Browser-hosted viewers (Chrome Remote Desktop at remotedesktop.google.com) are the
// same class but share the browser's bundle id, so they are matched by URL / hosted
// app-id rather than by `com.google.Chrome` itself — composing in the rest of Chrome
// must keep working.

import Foundation

public enum ClientPolicy {

    /// Built-in force-passthrough list. Best-effort but conservative: these are
    /// specific reverse-DNS ids that will not collide with ordinary apps.
    public static let forcePassthroughBundleIDs: Set<String> = [
        // Microsoft Remote Desktop (legacy) and Windows App (new) share this id.
        "com.microsoft.rdc.macos",
        "com.microsoft.rdc.osx.beta",
        // Virtualization
        "com.parallels.desktop.console",
        "com.parallels.macvm",          // cửa sổ VM macOS của Parallels (mỗi VM một tiến trình, chung id) — #128
        "com.vmware.fusion",
        "com.utmapp.UTM",
        // Screen sharing / remote control
        "com.apple.ScreenSharing",
        "com.citrix.receiver.icaviewer.mac",
        "com.teamviewer.TeamViewer",
        "com.realvnc.vncviewer",
        "com.nulana.remotixmac",
        "com.carriez.rustdesk",
        "com.philandro.anydesk",
        // NOT here: com.apple.ScreenContinuity (iPhone Mirroring). It was assumed
        // remote-desktop-class (raw scancodes to the phone) until maintainer
        // field-test 07/08/2026 showed it bridges Continuity to a REAL text field
        // — typing in-place works cleanly. See typing-modes.yml for the inPlace rule.
    ]

    /// Chrome Web Store / Chrome App ids whose window is a Chrome Remote Desktop
    /// viewer (not ordinary Chrome). Used both as `chrome-extension://` hosts and as
    /// substrings of PWA / "Open as window" bundle ids
    /// (`com.google.Chrome.app.<id>`).
    /// `gbchcmhmhahfdphkhkmpfmiifomcnacc` is the legacy Chrome App; the current
    /// companion extension is `inomeogfingihgjfjlpeplalcfajhgai`. The official
    /// "Install app" PWA is `cmkncekebbebpfilplodngbpllndjkfo` — field 18/09/2026:
    /// bundle `com.google.Chrome.app.cmkncekebbebpfilplodngbpllndjkfo`. Without
    /// that id the PWA is an unknown app → TAP (no URL scan), so local
    /// Backspace+retype fights the guest IME (`thuw` → `tha`: unicode insert
    /// posts `virtualKey: 0` = `kVK_ANSI_A`).
    public static let chromeRemoteDesktopExtensionIDs: Set<String> = [
        "inomeogfingihgjfjlpeplalcfajhgai",
        "gbchcmhmhahfdphkhkmpfmiifomcnacc",
        "cmkncekebbebpfilplodngbpllndjkfo",
    ]

    /// True when the built-in list marks this client as force-passthrough.
    public static func isRemoteDesktop(_ bundleID: String?) -> Bool {
        guard let id = bundleID else { return false }
        if forcePassthroughBundleIDs.contains(id) { return true }
        return isChromeRemoteDesktopApp(id)
    }

    /// A dedicated Chrome Remote Desktop window (PWA / Chrome App shortcut), not a
    /// normal Chrome tab. Normal `com.google.Chrome` is NOT this — those tabs are
    /// classified by URL instead (`isRemoteDesktopURL`).
    public static func isChromeRemoteDesktopApp(_ bundleID: String?) -> Bool {
        guard let id = bundleID else { return false }
        for ext in chromeRemoteDesktopExtensionIDs where id.contains(ext) { return true }
        return false
    }

    /// True when this web-area URL is a scancode tunnel to another machine — local
    /// composition (especially tap Backspace+retype) would fight the remote IME:
    /// diacritic keys jump the caret. Widen only with field evidence.
    public static func isRemoteDesktopURL(_ url: URL?) -> Bool {
        guard let url else { return false }
        let scheme = url.scheme?.lowercased()
        guard let host = url.host?.lowercased() else { return false }
        if isChromeRemoteDesktopHost(host) { return true }
        if scheme == "chrome-extension" && chromeRemoteDesktopExtensionIDs.contains(host) {
            return true
        }
        return false
    }

    /// `remotedesktop.google.com` and its subdomains (and the corp equivalent).
    /// `fakeremotedesktop.google.com` / `remotedesktop.google.com.evil.com` must
    /// NOT match — same suffix discipline as `markedFieldURL`.
    public static func isChromeRemoteDesktopHost(_ host: String) -> Bool {
        host == "remotedesktop.google.com"
            || host.hasSuffix(".remotedesktop.google.com")
            || host == "remotedesktop.corp.google.com"
            || host.hasSuffix(".remotedesktop.corp.google.com")
    }
}
