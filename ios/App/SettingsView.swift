import SwiftUI
import MojianCore

struct SettingsView: View {
    @State var settings: Settings
    var save: (Settings) -> Void
    @Environment(\.dismiss) var dismiss
    var body: some View {
        NavigationStack {
            Form {
                Section("书写") {
                    Toggle("手指书写",isOn:$settings.fingerWriting)
                    Text("关闭时，手指选择内容，双指平移或缩放。iPhone 可以开启手指书写。").font(.footnote).foregroundStyle(.secondary)
                    Toggle("笔迹预测",isOn:$settings.prediction)
                    slider("钢笔宽度",value:$settings.widthMM,range:0.1...2,step:0.1,suffix:"mm")
                    slider("压感",value:$settings.pressurePercent,range:0...400,step:10,suffix:"%")
                    slider("墨水流量",value:$settings.flowPercent,range:0...200,step:10,suffix:"%")
                    slider("荧光笔宽度",value:$settings.highlighterWidthMM,range:1...20,step:0.5,suffix:"mm")
                    slider("荧光笔不透明度",value:$settings.highlighterOpacity,range:5...100,step:1,suffix:"%")
                    Toggle("停笔转直线",isOn:$settings.straighten)
                    slider("停笔时间",value:$settings.straightenMS,range:200...2000,step:50,suffix:"ms")
                    Picker("图形",selection:$settings.shape) { ForEach(ShapeKind.allCases,id:\.self) { Text($0.title).tag($0) } }
                }
                Section("橡皮") {
                    slider("橡皮半径",value:$settings.eraserRadiusMM,range:0.25...40,step:0.25,suffix:"mm")
                    Toggle("按压临时橡皮",isOn:$settings.pressureErase)
                    slider("压力阈值",value:$settings.pressureThreshold,range:0.4...1,step:0.01,suffix:"")
                    slider("触发停留",value:$settings.dwellMS,range:50...500,step:10,suffix:"ms")
                    slider("触发前路程上限",value:$settings.travelMM,range:0.1...10,step:0.1,suffix:"mm")
                }
                Section("Apple Pencil 侧面操作") {
                    actionPicker("双击笔身",binding:$settings.doubleTap)
                    actionPicker("挤压笔身",binding:$settings.squeeze)
                    Text("双击需要 Apple Pencil 第 2 代或 Pro；挤压需要 Pencil Pro。第 1 代和 USB-C 型号没有这些侧面操作。设为系统快捷指令的挤压可能不会发送给应用。").font(.footnote).foregroundStyle(.secondary)
                }
                Section("第三方笔快捷键") {
                    TextField("快捷键 1 的字符",text:$settings.penKeyOne).textInputAutocapitalization(.never).autocorrectionDisabled()
                    actionPicker("快捷键 1",binding:$settings.penKeyOneAction)
                    TextField("快捷键 2 的字符",text:$settings.penKeyTwo).textInputAutocapitalization(.never).autocorrectionDisabled()
                    actionPicker("快捷键 2",binding:$settings.penKeyTwoAction)
                    Text("仅适用于侧键向 iOS 发送键盘字符的笔。留空不拦截；蓝牙私有侧键需要厂商协议。普通触控笔不能获得 Apple Pencil 压感。").font(.footnote).foregroundStyle(.secondary)
                }
                Section("关于") {
                    Text("墨笺 iOS 0.1.0")
                    NavigationLink("第三方许可") {
                        ScrollView {
                            Text((try? String(contentsOf:Bundle.main.url(forResource:"THIRD_PARTY_NOTICES",withExtension:"md") ?? URL(fileURLWithPath:"/unavailable"))) ?? "")
                                .font(.system(.footnote,design:.monospaced)).padding().textSelection(.enabled)
                        }.navigationTitle("第三方许可")
                    }
                }
                Section("导航") {
                    Toggle("递归显示页面",isOn:$settings.recursivePages)
                    Toggle("锁定双指缩放",isOn:$settings.pinchLocked)
                    slider("抓手灵敏度",value:$settings.handSensitivity,range:0.25...3,step:0.05,suffix:"")
                }
            }
            .navigationTitle("选项").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement:.confirmationAction) { Button("完成") { save(settings); dismiss() } } }
        }.tint(Color(red:0.73,green:0.64,blue:0.89)).preferredColorScheme(.dark)
    }
    private func actionPicker(_ title:String,binding:Binding<PencilAction>) -> some View {
        Picker(title,selection:binding) { ForEach(PencilAction.allCases,id:\.self) { Text($0.title).tag($0) } }
    }
    private func slider(_ title:String,value:Binding<Double>,range:ClosedRange<Double>,step:Double,suffix:String)->some View {
        VStack(alignment:.leading) {
            HStack { Text(title); Spacer(); Text("\(value.wrappedValue.formatted(.number.precision(.fractionLength(0...2)))) \(suffix)").monospacedDigit().foregroundStyle(.secondary) }
            Slider(value:value,in:range,step:step)
        }
    }
}
