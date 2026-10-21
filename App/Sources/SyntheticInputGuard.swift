// SyntheticInputGuard.swift — ngừng MỌI phím giả lập khi một app chống-giả-lập đang
// có cửa sổ trên màn hình.
//
// Little Snitch (field 22 + 25/09/2026): "Simulated Input Ignored — From: VietTelex".
// Nó từ chối phím synthetic khi cảnh báo kết nối / cửa sổ của nó đang mở (chống app
// khác bấm "Allow" hộ). Rule inPlace cho LS Agent chỉ phủ việc GÕ TRONG LS; hai
// nguồn còn lại vẫn chạm tới nó: phím F20 kiểm tra sức khoẻ của watchdog (3s/lần khi
// đang gõ, bất kể app nào ở trước) và backspace-retype của tap khi cảnh báo LS
// (panel không kích hoạt app) nổi lên đúng lúc gõ ở Chrome.
//
// Chi phí (đo 25/09/2026): ~0,5 ms CPU mỗi refresh, CHỈ khi đang gõ (tick watchdog
// 3s, nhánh không-idle) — máy không ai gõ thì 0. Hot path của tap chỉ đọc một Bool.

import Cocoa

enum SyntheticInputGuard {
    /// Bundle-id prefixes of apps that reject synthetic input.
    static let guardBundlePrefixes = ["at.obdev.littlesnitch"]

    /// Menu-bar layer (status items sit here forever) — never counts as "a window up".
    static let statusLayer = 25

    private static let lock = NSLock()
    nonisolated(unsafe) private static var _active = false
    static var isActive: Bool { lock.withLock { _active } }

    static func isGuardApp(_ bundleID: String?) -> Bool {
        guard let id = bundleID else { return false }
        return guardBundlePrefixes.contains { id.hasPrefix($0) }
    }

    /// Lớp cửa sổ thường (app window). Cửa sổ thường của app chống-giả-lập (vd Little Snitch
    /// Network Monitor để mở cả ngày) chỉ tính khi app đó đang ở trước — không thì nó chẳng nhận
    /// phím của ai, mà chặn thì tap mất dấu ở MỌI app (Phil 21/10/2026: Ghostty mất tiếng Việt).
    static let normalLayer = 0

    /// Pure: có cửa sổ của app chống-giả-lập đang cần chặn không? Cảnh báo / panel nổi (lớp > 0,
    /// trừ thanh menu) luôn tính; cửa sổ thường chỉ tính khi app đó đang ở trước.
    static func guardWindowUp(windows: [(ownerPID: pid_t, layer: Int)], guardPIDs: Set<pid_t>,
                              frontmostPID: pid_t? = nil) -> Bool {
        guard !guardPIDs.isEmpty else { return false }
        return windows.contains { w in
            guard guardPIDs.contains(w.ownerPID), w.layer != statusLayer else { return false }
            return w.layer != normalLayer || w.ownerPID == frontmostPID
        }
    }

    /// Idle: forget the last verdict (no scan while nobody types).
    static func clear() { lock.withLock { _active = false } }

    /// Recompute. Call off the tap thread (watchdog tick, typing-active only).
    static func refresh() {
        let pids = Set(NSWorkspace.shared.runningApplications
            .filter { isGuardApp($0.bundleIdentifier) }
            .map(\.processIdentifier))
        var up = false
        if !pids.isEmpty {
            let info = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements],
                                                  kCGNullWindowID) as? [[String: Any]] ?? []
            let windows = info.compactMap { w -> (ownerPID: pid_t, layer: Int)? in
                guard let pid = w[kCGWindowOwnerPID as String] as? Int32,
                      let layer = w[kCGWindowLayer as String] as? Int else { return nil }
                return (pid, layer)
            }
            up = guardWindowUp(windows: windows, guardPIDs: pids,
                               frontmostPID: NSWorkspace.shared.frontmostApplication?.processIdentifier)
        }
        let changed: Bool = lock.withLock { defer { _active = up }; return _active != up }
        if changed { DebugLog.log("synthetic-input guard \(up ? "ON (Little Snitch window up)" : "OFF")") }
    }
}
