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

/// Tệp tải về khai báo trong theme: bao nhiêu tệp, từ đâu, lỗi / cảnh báo lần tải trước. Không gửi yêu cầu nào —
/// chỉ khi lưu / thêm, app mới HỎI rồi mới tải.
struct ThemeAssetNote: View {
    let assets: ThemeAssetSpec
    @ObservedObject private var center = ThemeAssetCenter.shared
    var body: some View {
        let files = ThemeAssetPlan.make(assets).files
        if !files.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                let hosts = Array(Set(files.map { ThemeAssetURL.host($0.ref.url) })).sorted().joined(separator: ", ")
                Label(L("Giao diện có %@ tệp tải về từ %@ — sẽ hỏi trước khi tải.", files.count, hosts),
                      systemImage: "arrow.down.circle").font(.footnote).foregroundStyle(.secondary)
                ForEach(Array(center.errors(for: assets).enumerated()), id: \.offset) { _, e in
                    Label(ThemeAssetURL.host(e.0) + ": " + e.1.message, systemImage: "xmark.octagon.fill")
                        .foregroundStyle(.red).font(.footnote)
                }
                ForEach(Array(Set(center.storedWarnings(assets).map(\.rawValue)).sorted()), id: \.self) { w in
                    Label(ThemeAssetWarning(rawValue: w)?.message ?? w, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange).font(.footnote)
                }
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
                        ForEach([false, true], id: \.self) { dark in
                            ThemePreview(palette: spec.palette(dark: dark), wallpaper: nil, dim: 0, large: false,
                                         keyStyle: spec.typography)
                                .clipShape(RoundedRectangle(cornerRadius: 8))
                        }
                    }
                    .frame(height: 70)
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
                if let spec = outcome.spec { ThemeAssetNote(assets: spec.assets).frame(maxWidth: .infinity, alignment: .leading) }
            }
            .padding()
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .onChange(of: text) { new in
                saveIssues = []
                // Bàn phím iOS hay tự đổi " thành “ ” (dấu ngoặc cong) ⇒ JSON sai — đổi lại ngay.
                let fixed = new.replacingOccurrences(of: "“", with: "\"").replacingOccurrences(of: "”", with: "\"")
                if fixed != new { text = fixed }
            }
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
                            ForEach([false, true], id: \.self) { dark in
                                ThemePreview(palette: spec.palette(dark: dark), wallpaper: nil, dim: 0, large: false,
                                             keyStyle: spec.typography)
                                    .clipShape(RoundedRectangle(cornerRadius: 8))
                            }
                        }
                        .frame(height: 90)
                        .listRowInsets(EdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 8))
                    } header: { Text(spec.name) } footer: { Text(L("Sáng · Tối")) }
                }
                Section {
                    ThemeIssueList(issues: addError.map { [$0] } ?? outcome.issues)
                    if let spec = outcome.spec { ThemeAssetNote(assets: spec.assets) }
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
