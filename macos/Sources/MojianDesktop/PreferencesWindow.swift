import AppKit
import MojianCore

/// macOS Settings window (⌘,). It replaces the Windows Backstage `选项` panel for device-local
/// preferences; every change applies to all open note windows.
@MainActor final class PreferencesWindow: NSWindowController {
    private let current: () -> DesktopSession?
    private let apply: ((DesktopSession) -> Void) -> Void
    private let pageMode = NSPopUpButton(), background = NSPopUpButton(), theme = NSPopUpButton(), mouse = NSPopUpButton()
    private let pinch = NSButton(checkboxWithTitle: "锁定双指缩放比例", target: nil, action: nil)
    init(current: @escaping () -> DesktopSession?, apply: @escaping ((DesktopSession) -> Void) -> Void) {
        self.current = current; self.apply = apply
        let window = NSWindow(contentRect: CGRect(x: 0, y: 0, width: 460, height: 250), styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.title = "设置"; window.isReleasedWhenClosed = false; window.appearance = NSAppearance(named: .darkAqua)
        super.init(window: window)
        pageMode.addItems(withTitles: ["本层", "递归"])
        for b in Background.allCases { background.addItem(withTitle: b.title); background.lastItem?.representedObject = b.rawValue }
        theme.addItems(withTitles: ["暗色", "浅色"])
        mouse.addItems(withTitles: ["选择对象", "使用当前工具书写"])
        for control in [pageMode, background, theme, mouse, pinch] { control.target = self; control.action = #selector(changed(_:)) }
        func label(_ text: String) -> NSTextField { let l = NSTextField(labelWithString: text); l.alignment = .right; return l }
        let grid = NSGridView(views: [
            [label("页面列表："), pageMode], [label("新建页默认背景："), background], [label("页面主题（所有页面）："), theme],
            [label("鼠标与触控板："), mouse], [NSGridCell.emptyContentView, pinch]
        ])
        grid.rowSpacing = 12; grid.columnSpacing = 10; grid.column(at: 0).xPlacement = .trailing; grid.translatesAutoresizingMaskIntoConstraints = false
        let content = NSView(); content.addSubview(grid)
        NSLayoutConstraint.activate([grid.centerXAnchor.constraint(equalTo: content.centerXAnchor), grid.topAnchor.constraint(equalTo: content.topAnchor, constant: 24),
                                     grid.bottomAnchor.constraint(lessThanOrEqualTo: content.bottomAnchor, constant: -20)])
        window.contentView = content; window.center()
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }
    func reload() {
        guard let s = current() else { for c in [pageMode, background, theme, mouse, pinch] { c.isEnabled = false }; return }
        for c in [pageMode, background, theme, mouse, pinch] { c.isEnabled = true }
        pageMode.selectItem(at: s.settings.recursivePages ? 1 : 0)
        background.selectItem(at: Background.allCases.firstIndex { $0.rawValue == s.defaultBackground } ?? 0)
        theme.selectItem(at: s.dark ? 0 : 1); mouse.selectItem(at: s.mouseWriting ? 1 : 0)
        pinch.state = s.settings.pinchLocked ? .on : .off
    }
    override func showWindow(_ sender: Any?) { reload(); super.showWindow(sender); window?.makeKeyAndOrderFront(sender) }
    @objc private func changed(_ sender: Any?) {
        let recursive = pageMode.indexOfSelectedItem == 1, defaultBackground = background.selectedItem?.representedObject as? String ?? "GRID_STANDARD"
        let dark = theme.indexOfSelectedItem == 0, writing = mouse.indexOfSelectedItem == 1, locked = pinch.state == .on
        apply { s in
            s.settings.recursivePages = recursive; s.defaultBackground = defaultBackground; s.dark = dark
            s.mouseWriting = writing; s.settings.pinchLocked = locked; s.savePreferences()
        }
    }
}
