// "Chọn giao diện": lưới 2 cột thẻ xem trước (vẽ bằng token theme, không ảnh), chia "Miễn phí" / "Plus" /
// "Giao diện của bạn". Chạm là chọn ngay và ở lại trang (thẻ đang chọn có viền + dấu ✓). Theme Plus bị
// khoá: mờ + nhãn Plus, chạm mở màn VietTelex Plus (như Android).
// Mỗi thẻ có menu ⋯ ("Giao diện JSON"): Chép JSON (miễn phí) · Sửa · Đặt lại mặc định (theme có sẵn đã
// chỉnh) · Xoá (theme tự tạo) — trừ Chép, mọi mục cần Plus. "Dán giao diện" chỉ đọc clipboard khi bấm.
import SwiftUI
import UIKit

private let pickerDefaults = UserDefaults(suiteName: "group.com.viettelex")

struct ThemePickerView: View {
    @Binding var settings: ThemeSettings
    let onChange: () -> Void
    @Environment(\.colorScheme) private var scheme
    /// Chạm thẻ Plus bị khoá ⇒ mở màn VietTelex Plus (cùng màn PlusEntryRow mở).
    @State private var showPlus = false
    @State private var store = CustomThemeStore.load(pickerDefaults)
    @State private var editing: Editing?
    @State private var pasteText: PasteText?
    @State private var toast: String?
    @State private var confirmDelete: String?

    struct Editing: Identifiable { let id = UUID(); let target: ThemeEditTarget; let title: String; let text: String }
    struct PasteText: Identifiable { let id = UUID(); let text: String }

    private let columns = [GridItem(.flexible(), spacing: 14), GridItem(.flexible(), spacing: 14)]

    private var customs: [ThemeSpec] {
        store.values.sorted { ($0.name, $0.id ?? "") < ($1.name, $1.id ?? "") }
    }

    var body: some View {
        List {
            if !ThemeGate.allowsWallpaper {
                Section {
                    PlusEntryRow()
                } footer: {
                    Text(L("Theme có nhãn Plus và ảnh nền thuộc VietTelex Plus. Hệ thống, Tối OLED và Tương phản cao luôn miễn phí."))
                }
            }
            ForEach(ThemePicker.sections, id: \.title) { section in
                Section {
                    LazyVGrid(columns: columns, spacing: 14) {
                        ForEach(section.themes, id: \.self) { builtinCard($0) }
                    }
                    .padding(.vertical, 6)
                } header: { Text(L(section.title)) }
            }
            Section {
                if !customs.isEmpty {
                    LazyVGrid(columns: columns, spacing: 14) {
                        ForEach(customs, id: \.id) { customCard($0) }
                    }
                    .padding(.vertical, 6)
                }
                Button { paste() } label: {
                    HStack(spacing: 6) {
                        Label(L("Dán giao diện"), systemImage: "doc.on.clipboard")
                        if !ThemeGate.allowsCustom { plusBadge }
                    }
                }
            } header: { Text(L("Giao diện của bạn")) } footer: {
                Text(L("Dán JSON giao diện đã chép (Plus). Chỉ đọc clipboard khi bạn bấm, xem trước rồi mới thêm."))
            }
        }
        .navigationTitle(L("Chọn giao diện"))
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(isPresented: $showPlus) { PlusView(model: PlusShared.model) }
        .sheet(item: $editing) { e in
            ThemeJSONEditorView(title: e.title, text: e.text) { text in
                let r = ThemeJSONActions.save(text, to: e.target, settings: &settings, store: &store)
                if r.ok { persist() }
                return r.issues
            }
        }
        .sheet(item: $pasteText) { p in
            ThemePastePreview(text: p.text) { spec in
                switch ThemeJSONActions.commitPaste(spec, store: &store) {
                case .failure(let e): return e
                case .success(let id):
                    if let s = store[id], let next = ThemePicker.select(custom: s, in: settings) { settings = next }
                    persist()
                    return nil
                }
            }
        }
        .alert(L("Xoá giao diện này?"), isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } })) {
            Button(L("Xoá"), role: .destructive) {
                if let id = confirmDelete { ThemeJSONActions.delete(id, settings: &settings, store: &store); persist() }
                confirmDelete = nil
            }
            Button(L("Huỷ"), role: .cancel) { confirmDelete = nil }
        }
        .overlay(alignment: .bottom) {
            if let toast {
                Text(toast).font(.footnote).padding(.horizontal, 14).padding(.vertical, 8)
                    .background(Capsule().fill(.thinMaterial)).padding(.bottom, 24)
                    .task { try? await Task.sleep(nanoseconds: 1_600_000_000); self.toast = nil }
            }
        }
    }

    private func persist() {
        CustomThemeStore.save(store, pickerDefaults)
        onChange()
    }

    private func paste() {
        guard ThemeGate.allowsCustom else { showPlus = true; return }
        // Chỉ đọc clipboard ở đây (người dùng vừa bấm "Dán giao diện").
        guard let s = UIPasteboard.general.string, !s.isEmpty else { toast = L("Clipboard không có văn bản."); return }
        pasteText = PasteText(text: s)
    }

    private func copy(_ t: ThemeEditTarget) {
        guard let text = ThemeJSONActions.copyText(t, settings: settings, store: store) else { return }
        UIPasteboard.general.string = text
        toast = L("Đã chép JSON giao diện.")
    }

    private func edit(_ t: ThemeEditTarget, title: String) {
        guard ThemeGate.allowsCustom else { showPlus = true; return }
        guard let text = ThemeJSONActions.copyText(t, settings: settings, store: store) else { return }
        editing = Editing(target: t, title: title, text: text)
    }

    @ViewBuilder private func menu(_ t: ThemeEditTarget, title: String) -> some View {
        Menu {
            Button { copy(t) } label: { Label(L("Chép JSON"), systemImage: "doc.on.doc") }
            Button { edit(t, title: title) } label: {
                Label(ThemeGate.allowsCustom ? L("Sửa") : L("Sửa") + " · Plus", systemImage: "curlybraces")
            }
            switch t {
            case .builtin(let b):
                if settings.keyStyles[b.rawValue] != nil {
                    Button(role: .destructive) {
                        guard ThemeGate.allowsAdvanced else { showPlus = true; return }
                        ThemeJSONActions.reset(b, settings: &settings); onChange()
                    } label: { Label(L("Đặt lại mặc định"), systemImage: "arrow.uturn.backward") }
                }
            case .custom(let id):
                Button(role: .destructive) {
                    guard ThemeGate.allowsCustom else { showPlus = true; return }
                    confirmDelete = id
                } label: { Label(L("Xoá"), systemImage: "trash") }
            }
        } label: {
            Image(systemName: "ellipsis.circle").font(.body).foregroundStyle(.secondary)
                .frame(width: 28, height: 24).contentShape(Rectangle())
        }
        .accessibilityLabel(L("Tuỳ chọn giao diện %@", title))
    }

    private func builtinCard(_ t: KeyboardTheme) -> some View {
        let selected = settings.custom == nil && settings.theme == t
        let locked = !ThemeGate.allows(t)
        return cardBody(palette: settings.cardPalette(for: t, systemDark: scheme == .dark), style: settings.effectiveStyle(for: t),
                        title: t.title, selected: selected, locked: locked, showPlus: t.isPlus && locked,
                        menu: menu(.builtin(t), title: t.title)) {
            switch ThemePicker.tap(t, in: settings) {
            case .select(let next): settings = next; onChange()
            case .openPlus: showPlus = true
            }
        }
    }

    private func customCard(_ c: ThemeSpec) -> some View {
        let id = c.id ?? ""
        let selected = settings.custom?.id == id
        let locked = !ThemeGate.allowsCustom
        return cardBody(palette: settings.cardPalette(custom: c, systemDark: scheme == .dark),
                        style: settings.effectiveStyle(id: id, typography: c.typography),
                        title: c.name, selected: selected, locked: locked, showPlus: locked,
                        menu: menu(.custom(id), title: c.name)) {
            if let next = ThemePicker.select(custom: c, in: settings) { settings = next; onChange() } else { showPlus = true }
        }
    }

    private func cardBody<M: View>(palette: KeyboardPalette, style: KeyStyle, title: String, selected: Bool, locked: Bool,
                                   showPlus: Bool, menu: M, tap: @escaping () -> Void) -> some View {
        VStack(spacing: 6) {
            Button(action: tap) {
                // Thẻ phản ánh tuỳ chỉnh riêng của theme đó (rẻ: vài phép màu, không ảnh).
                ThemePreview(palette: palette, wallpaper: nil, dim: 0, large: false, keyStyle: style)
                    .frame(height: 78)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10)
                        .stroke(selected ? Color.accentColor : Color.secondary.opacity(0.3), lineWidth: selected ? 3 : 1))
                    .overlay(alignment: .topTrailing) {
                        if selected {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(.white, Color.accentColor).font(.title3).padding(4)
                        }
                    }
                    .opacity(locked ? 0.5 : 1)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(title + (selected ? L(", đang chọn") : "") + (locked ? " · Plus" : ""))
            .accessibilityAddTraits(selected ? .isSelected : [])
            HStack(spacing: 4) {
                Text(title).font(.footnote).lineLimit(1)
                if showPlus { plusBadge }
                Spacer(minLength: 0)
                menu
            }
            .foregroundStyle(.primary)
        }
    }

    private var plusBadge: some View {
        Text("Plus").font(.caption2.weight(.bold))
            .padding(.horizontal, 5).padding(.vertical, 1)
            .background(Capsule().fill(Color.orange.opacity(0.85)))
            .foregroundStyle(.white)
    }
}
