import UIKit

/// Bàn phím tối hay sáng. Host đa số truyền `.default` → theo trait của CONTROLLER
/// (host/hệ thống). Không dùng trait của KeyboardView: lúc viewWillAppear view chưa
/// vào window nên trait `.unspecified` ⇒ phím sáng trên nền tối (bug 26/09/2026).
enum AppearancePolicy {
    static func isDark(appearance: UIKeyboardAppearance, style: UIUserInterfaceStyle) -> Bool {
        switch appearance {
        case .dark: return true
        case .light: return false
        default: return style == .dark
        }
    }

    /// MỘT nguồn sáng/tối cho mỗi lần hiện (bug Phil 19/10/2026: nền + thanh gợi ý TỐI nhưng phím SÁNG,
    /// chập chờn). Trước đây mỗi chỗ đọc một kiểu: viewDidAppear / đổi trait đọc appearance TRỰC TIẾP
    /// từ proxy + trait controller, còn viewDidLayoutSubviews đọc appearance ĐÃ LƯU của ô (fieldTraits)
    /// + trait của view ⇒ hai nguồn lệch (ô trước xin .light, ô mới .default máy tối; hoặc trait view ≠
    /// trait controller lúc vừa vào window) thì mỗi lượt layout lật qua lật lại, có lúc dừng ở sáng.
    /// Giờ MỌI chỗ gọi hàm này với appearance của ô (fieldTraits — đọc lại ở textDidChange / didAppear)
    /// và trait của view đã vào window (chưa vào / .unspecified ⇒ trait controller).
    static func presentationDark(fieldAppearance: UIKeyboardAppearance?, viewStyle: UIUserInterfaceStyle,
                                 controllerStyle: UIUserInterfaceStyle) -> Bool {
        let style = viewStyle == .unspecified ? controllerStyle : viewStyle
        return isDark(appearance: fieldAppearance ?? .default, style: style)
    }
}
