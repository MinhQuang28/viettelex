// "Giao diện JSON" (Plus): trình sửa JSON theme (chữ đơn cách, kiểm trực tiếp) + xem trước khi dán.
// Logic thuần nằm ở Keyboard/ThemeSpec.swift (ThemeSpecParser, ThemeJSONActions) — test ThemeJSONTests.
import SwiftUI

/// Danh sách lỗi / cảnh báo (tiếng Việt hoặc tiếng Anh theo ngôn ngữ giao diện).
struct ThemeIssueList: View {
    let issues: [ThemeIssue]
    var body: some View {
        if issues.isEmpty {
            Label(L("JSON hợp lệ."), systemImage: "checkmark.circle.fill").foregroundStyle(.green).font(.footnote)
        } else {
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(issues.prefix(8).enumerated()), id: \.offset) { _, i in
                    Label(i.message, systemImage: i.isError ? "xmark.octagon.fill" : "exclamationmark.triangle.fill")
                        .foregroundStyle(i.isError ? .red : .orange).font(.footnote)
                }
                if issues.count > 8 { Text(L("… và %@ mục khác.", issues.count - 8)).font(.footnote).foregroundStyle(.secondary) }
            }
        }
    }
}

/// Sửa JSON một theme. Lưu chỉ bật khi không còn lỗi; cảnh báo (tương phản thấp…) vẫn lưu được.
struct ThemeJSONEditorView: View {
    let title: String
    @State var text: String
    /// Trả lỗi nếu lưu không được (giữ trình sửa mở), rỗng = xong.
    let onSave: (String) -> [ThemeIssue]
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    @State private var saveIssues: [ThemeIssue] = []

    private var outcome: ThemeSpecParser.Outcome { ThemeSpecParser.parse(text) }

    var body: some View {
        NavigationStack {
            VStack(spacing: 8) {
                if let spec = outcome.spec {
                    HStack(spacing: 8) {
                        ThemePreview(palette: spec.palette(dark: false), wallpaper: nil, dim: 0, large: false,
                                     keyStyle: spec.typography)
                        ThemePreview(palette: spec.palette(dark: true), wallpaper: nil, dim: 0, large: false,
                                     keyStyle: spec.typography)
                    }
                    .frame(height: 70).clipShape(RoundedRectangle(cornerRadius: 8))
                }
                TextEditor(text: $text)
                    .font(.system(.footnote, design: .monospaced))
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .scrollContentBackground(.hidden)
                    .padding(6)
                    .background(RoundedRectangle(cornerRadius: 8).fill(Color(.secondarySystemBackground)))
                ThemeIssueList(issues: saveIssues.isEmpty ? outcome.issues : saveIssues)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding()
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .onChange(of: text) { _ in saveIssues = [] }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("Huỷ")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("Lưu")) {
                        let r = onSave(text)
                        if r.contains(where: \.isError) { saveIssues = r } else { dismiss() }
                    }
                    .disabled(!outcome.errors.isEmpty)
                }
            }
        }
    }
}

/// "Dán giao diện": xem trước theme đọc từ clipboard (chỉ đọc khi người dùng bấm) — Lưu mới ghi.
struct ThemePastePreview: View {
    let text: String
    let onAdd: (ThemeSpec) -> ThemeIssue?
    @Environment(\.dismiss) private var dismiss
    @State private var addError: ThemeIssue?

    private var outcome: ThemeSpecParser.Outcome { ThemeJSONActions.preview(text) }

    var body: some View {
        NavigationStack {
            List {
                if let spec = outcome.spec {
                    Section {
                        HStack(spacing: 8) {
                            ThemePreview(palette: spec.palette(dark: false), wallpaper: nil, dim: 0, large: false,
                                         keyStyle: spec.typography)
                            ThemePreview(palette: spec.palette(dark: true), wallpaper: nil, dim: 0, large: false,
                                         keyStyle: spec.typography)
                        }
                        .frame(height: 90)
                        .listRowInsets(EdgeInsets())
                    } header: { Text(spec.name) } footer: { Text(L("Sáng · Tối")) }
                }
                Section {
                    ThemeIssueList(issues: addError.map { [$0] } ?? outcome.issues)
                }
            }
            .navigationTitle(L("Dán giao diện"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("Huỷ")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("Thêm")) {
                        guard let spec = outcome.spec else { return }
                        if let e = onAdd(spec) { addError = e } else { dismiss() }
                    }
                    .disabled(outcome.spec == nil)
                }
            }
        }
    }
}
