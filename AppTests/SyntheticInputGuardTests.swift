import XCTest
@testable import VietTelex

/// Little Snitch "Simulated Input Ignored — From: VietTelex" (22 + 25/09/2026).
final class SyntheticInputGuardTests: XCTestCase {
    func testGuardApps() {
        XCTAssertTrue(SyntheticInputGuard.isGuardApp("at.obdev.littlesnitch.agent"))
        XCTAssertTrue(SyntheticInputGuard.isGuardApp("at.obdev.littlesnitch"))
        XCTAssertTrue(SyntheticInputGuard.isGuardApp("at.obdev.littlesnitch.networkmonitor"))
        XCTAssertFalse(SyntheticInputGuard.isGuardApp("com.google.Chrome"))
        XCTAssertFalse(SyntheticInputGuard.isGuardApp(nil))
    }

    func testOnlyARealWindowCountsNotTheStatusItem() {
        let ls: Set<pid_t> = [42]
        // Status item in the menu bar only → typing unaffected.
        XCTAssertFalse(SyntheticInputGuard.guardWindowUp(windows: [(42, 25), (7, 0)], guardPIDs: ls))
        // Alert panel (floating layer) up → guard, whatever is in front.
        XCTAssertTrue(SyntheticInputGuard.guardWindowUp(windows: [(42, 8)], guardPIDs: ls))
        // Normal window (Network Monitor left open all day) only guards while LS is frontmost —
        // 21/10/2026 it stayed open behind Ghostty and silenced the tap everywhere.
        XCTAssertFalse(SyntheticInputGuard.guardWindowUp(windows: [(42, 0)], guardPIDs: ls, frontmostPID: 7))
        XCTAssertFalse(SyntheticInputGuard.guardWindowUp(windows: [(42, 0)], guardPIDs: ls))
        XCTAssertTrue(SyntheticInputGuard.guardWindowUp(windows: [(42, 0)], guardPIDs: ls, frontmostPID: 42))
        // Someone else's windows / guard app not running → no guard.
        XCTAssertFalse(SyntheticInputGuard.guardWindowUp(windows: [(7, 8)], guardPIDs: ls))
        XCTAssertFalse(SyntheticInputGuard.guardWindowUp(windows: [(42, 8)], guardPIDs: []))
    }
}
