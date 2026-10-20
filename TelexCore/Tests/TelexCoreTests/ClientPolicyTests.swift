import XCTest
@testable import TelexCore

final class ClientPolicyTests: XCTestCase {

    func testRemoteDesktopAppsAreForcePassthrough() {
        let rdpApps = [
            "com.microsoft.rdc.macos",       // Microsoft Remote Desktop / Windows App
            "com.microsoft.rdc.osx.beta",
            "com.parallels.desktop.console",
            "com.parallels.macvm",           // VM macOS của Parallels (#128)
            "com.vmware.fusion",
            "com.utmapp.UTM",
            "com.apple.ScreenSharing",
            "com.citrix.receiver.icaviewer.mac",
            "com.teamviewer.TeamViewer",
            "com.realvnc.vncviewer",
            "com.nulana.remotixmac",
            "com.carriez.rustdesk",
            "com.philandro.anydesk",
        ]
        for id in rdpApps {
            XCTAssertTrue(ClientPolicy.isRemoteDesktop(id), "should be RDP: \(id)")
        }
    }

    func testOrdinaryAppsAreNotForcePassthrough() {
        let normal = [
            "com.apple.TextEdit", "com.google.Chrome", "com.microsoft.Word",
            "com.apple.Terminal", "com.microsoft.VSCode", "com.tinyspeck.slackmacgap",
            "com.hnc.Discord", nil,
        ]
        for id in normal {
            XCTAssertFalse(ClientPolicy.isRemoteDesktop(id), "should NOT be RDP: \(id ?? "nil")")
        }
    }

    func testChromeRemoteDesktopPWABundleIsForcePassthrough() {
        // "Open as window" / PWA ids embed the Chrome App / extension id.
        XCTAssertTrue(ClientPolicy.isRemoteDesktop(
            "com.google.Chrome.app.Default-inomeogfingihgjfjlpeplalcfajhgai"))
        XCTAssertTrue(ClientPolicy.isRemoteDesktop(
            "com.google.Chrome.app.gbchcmhmhahfdphkhkmpfmiifomcnacc"))
        XCTAssertTrue(ClientPolicy.isChromeRemoteDesktopApp(
            "com.microsoft.edgemac.app.inomeogfingihgjfjlpeplalcfajhgai"))
        // Official Chrome Remote Desktop PWA ("Install app") — field 18/09/2026.
        XCTAssertTrue(ClientPolicy.isRemoteDesktop(
            "com.google.Chrome.app.cmkncekebbebpfilplodngbpllndjkfo"))
        XCTAssertTrue(ClientPolicy.isChromeRemoteDesktopApp(
            "com.google.Chrome.app.cmkncekebbebpfilplodngbpllndjkfo"))
        // Ordinary Chrome must keep axDetect — CRD tabs are URL-matched, not bundle-matched.
        XCTAssertFalse(ClientPolicy.isChromeRemoteDesktopApp("com.google.Chrome"))
        XCTAssertFalse(ClientPolicy.isRemoteDesktop("com.google.Chrome"))
    }

    func testChromeRemoteDesktopWebURLIsPassthrough() {
        XCTAssertTrue(ClientPolicy.isRemoteDesktopURL(
            URL(string: "https://remotedesktop.google.com/access")))
        XCTAssertTrue(ClientPolicy.isRemoteDesktopURL(
            URL(string: "https://remotedesktop.google.com/support")))
        XCTAssertTrue(ClientPolicy.isRemoteDesktopURL(
            URL(string: "https://www.remotedesktop.google.com/")))
        XCTAssertTrue(ClientPolicy.isRemoteDesktopURL(
            URL(string: "https://remotedesktop.corp.google.com/access")))
        XCTAssertTrue(ClientPolicy.isRemoteDesktopURL(
            URL(string: "chrome-extension://inomeogfingihgjfjlpeplalcfajhgai/index.html")))
        XCTAssertTrue(ClientPolicy.isRemoteDesktopURL(
            URL(string: "chrome-extension://gbchcmhmhahfdphkhkmpfmiifomcnacc/main.html")))
        XCTAssertTrue(ClientPolicy.isRemoteDesktopURL(
            URL(string: "chrome-extension://cmkncekebbebpfilplodngbpllndjkfo/index.html")))
    }

    func testChromeRemoteDesktopURLDoesNotOverMatch() {
        XCTAssertFalse(ClientPolicy.isRemoteDesktopURL(
            URL(string: "https://docs.google.com/document/d/abc/edit")))
        XCTAssertFalse(ClientPolicy.isRemoteDesktopURL(
            URL(string: "https://google.com/")))
        XCTAssertFalse(ClientPolicy.isRemoteDesktopURL(
            URL(string: "https://fakeremotedesktop.google.com/")))
        XCTAssertFalse(ClientPolicy.isRemoteDesktopURL(
            URL(string: "https://remotedesktop.google.com.evil.example/")))
        XCTAssertFalse(ClientPolicy.isRemoteDesktopURL(
            URL(string: "https://evil.example/remotedesktop.google.com")))
        XCTAssertFalse(ClientPolicy.isRemoteDesktopURL(
            URL(string: "chrome-extension://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/")))
        XCTAssertFalse(ClientPolicy.isRemoteDesktopURL(nil))
        XCTAssertFalse(ClientPolicy.isRemoteDesktopURL(URL(string: "https://example.com/")))
    }
}
