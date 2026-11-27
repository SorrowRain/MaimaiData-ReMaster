import SwiftUI

struct FinaleToDxView: View {
    @State private var tapP = ""; @State private var tapGr = ""; @State private var tapGd = ""; @State private var tapM = ""
    @State private var holdP = ""; @State private var holdGr = ""; @State private var holdGd = ""; @State private var holdM = ""
    @State private var slideP = ""; @State private var slideGr = ""; @State private var slideGd = ""; @State private var slideM = ""
    @State private var breakP = ""; @State private var breakGr = ""; @State private var breakGd = ""; @State private var breakM = ""
    @State private var breakScore = ""

    private var hasAnyInput: Bool {
        [
            tapP, tapGr, tapGd, tapM,
            holdP, holdGr, holdGd, holdM,
            slideP, slideGr, slideGd, slideM,
            breakP, breakGr, breakGd, breakM,
            breakScore
        ].contains { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
    }

    private var liveCalculation: (result: String, hint: String?, isError: Bool) {
        guard hasAnyInput else { return ("—", nil, false) }
        let counts = RatingCalculator.FinaleNoteCounts(
            tapPerfect: parseInt(tapP), tapGreat: parseInt(tapGr), tapGood: parseInt(tapGd), tapMiss: parseInt(tapM),
            holdPerfect: parseInt(holdP), holdGreat: parseInt(holdGr), holdGood: parseInt(holdGd), holdMiss: parseInt(holdM),
            slidePerfect: parseInt(slideP), slideGreat: parseInt(slideGr), slideGood: parseInt(slideGd), slideMiss: parseInt(slideM),
            breakPerfect: parseInt(breakP), breakGreat: parseInt(breakGr), breakGood: parseInt(breakGd), breakMiss: parseInt(breakM),
            breakScore: parseInt(breakScore)
        )
        do {
            let converted = try RatingCalculator.convertFinaleToDx(counts)
            return (converted, nil, false)
        } catch RatingCalculator.FinaleConversionError.missingNotesOrBreak {
            return ("—", "请至少填写 Break 判定数量以换算 DX 达成率", false)
        } catch {
            return ("—", error.localizedDescription, true)
        }
    }

    var body: some View {
        let calc = liveCalculation

        ScrollView {
            VStack(spacing: AppTheme.cardSpacing) {
                AppSectionCard(
                    title: "换算结果",
                    subtitle: "根据旧框判定实时换算对应的 DX 达成率区间或精确值"
                ) {
                    VStack(alignment: .leading, spacing: AppTheme.Space.s) {
                        AppInsetGroup(padding: AppTheme.Space.m) {
                            HStack {
                                Text("DX 达成率")
                                    .font(AppTheme.Typography.inlineAction)
                                    .foregroundStyle(.secondary)
                                Spacer()
                                Text(calc.result)
                                    .font(AppTheme.Typography.heroTitle)
                                    .foregroundStyle(AppTheme.accentText)
                            }
                        }

                        if let hint = calc.hint {
                            HStack(spacing: AppTheme.Space.xs) {
                                if calc.isError {
                                    Image(systemName: "exclamationmark.circle.fill")
                                        .font(AppTheme.Typography.caption)
                                        .foregroundStyle(AppTheme.destructive)
                                }
                                Text(hint)
                                    .font(AppTheme.Typography.caption)
                                    .foregroundStyle(calc.isError ? AppTheme.destructive : .secondary)
                            }
                        }
                    }
                }

                AppSectionCard(
                    title: "各音符判定数",
                    subtitle: "未填写的项默认按 0 计算"
                ) {
                    AppInlineButton(
                        title: "清空重填",
                        icon: "arrow.counterclockwise",
                        tint: .secondary,
                        isDisabled: !hasAnyInput
                    ) {
                        KeyboardDismiss.hide()
                        clearAll()
                    }
                } content: {
                    VStack(spacing: AppTheme.Space.m) {
                        noteGroup("TAP", color: AppTheme.Palette.noteTap, p: sanitizedBinding($tapP), gr: sanitizedBinding($tapGr), gd: sanitizedBinding($tapGd), m: sanitizedBinding($tapM))
                        AppInsetDivider()
                        noteGroup("HOLD", color: AppTheme.Palette.noteHold, p: sanitizedBinding($holdP), gr: sanitizedBinding($holdGr), gd: sanitizedBinding($holdGd), m: sanitizedBinding($holdM))
                        AppInsetDivider()
                        noteGroup("SLIDE", color: AppTheme.Palette.noteSlide, p: sanitizedBinding($slideP), gr: sanitizedBinding($slideGr), gd: sanitizedBinding($slideGd), m: sanitizedBinding($slideM))
                        AppInsetDivider()
                        noteGroup("BREAK", color: AppTheme.Palette.noteBreak, p: sanitizedBinding($breakP), gr: sanitizedBinding($breakGr), gd: sanitizedBinding($breakGd), m: sanitizedBinding($breakM))
                    }
                }

                AppSectionCard(
                    title: "Break 绝赞总分（可选）",
                    subtitle: "Break 全 Perfect 时填写旧框绝赞总分可计算精确值"
                ) {
                    AppTextField(
                        placeholder: "Break 总分（可留空或填 0）",
                        text: sanitizedBinding($breakScore, maxDigits: 7),
                        keyboardType: .numberPad
                    )
                }
            }
            .padding(.horizontal, AppTheme.pagePadding)
            .padding(.vertical, AppTheme.Space.m)
            .dismissKeyboardOnTap()
        }
        .scrollDismissesKeyboard(.interactively)
        .appPageBackground()
        .navigationTitle("旧框达成率换算")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func noteGroup(
        _ title: String,
        color: Color,
        p: Binding<String>,
        gr: Binding<String>,
        gd: Binding<String>,
        m: Binding<String>
    ) -> some View {
        VStack(alignment: .leading, spacing: AppTheme.Space.s) {
            HStack(spacing: AppTheme.Space.s) {
                Circle()
                    .fill(color)
                    .frame(width: 8, height: 8)
                Text(title)
                    .font(AppTheme.Typography.subheadlineBold)
                    .foregroundStyle(.primary)
            }

            HStack(spacing: AppTheme.Space.s) {
                AppTextField(title: "Perfect", placeholder: "0", text: p, keyboardType: .numberPad)
                AppTextField(title: "Great", placeholder: "0", text: gr, keyboardType: .numberPad)
                AppTextField(title: "Good", placeholder: "0", text: gd, keyboardType: .numberPad)
                AppTextField(title: "Miss", placeholder: "0", text: m, keyboardType: .numberPad)
            }
        }
    }

    private func sanitizedBinding(_ source: Binding<String>, maxDigits: Int = 4) -> Binding<String> {
        Binding(
            get: { source.wrappedValue },
            set: { newValue in
                source.wrappedValue = String(newValue.filter(\.isNumber).prefix(maxDigits))
            }
        )
    }

    private func clearAll() {
        tapP = ""; tapGr = ""; tapGd = ""; tapM = ""
        holdP = ""; holdGr = ""; holdGd = ""; holdM = ""
        slideP = ""; slideGr = ""; slideGd = ""; slideM = ""
        breakP = ""; breakGr = ""; breakGd = ""; breakM = ""
        breakScore = ""
    }

    private func parseInt(_ text: String) -> Int {
        Int(text.trimmingCharacters(in: .whitespaces)) ?? 0
    }
}
