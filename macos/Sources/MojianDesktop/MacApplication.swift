import AppKit
import MojianCore

@MainActor public final class MacApplication: NSObject, NSApplicationDelegate {
    private var controllers = [MacWindow]()
    private var welcome: NSWindow?
    private static var retained: MacApplication?
    public static func run() {
        let application = NSApplication.shared
        let delegate = MacApplication(); retained = delegate
        application.delegate = delegate; application.setActivationPolicy(.regular); application.run()
    }
    public func applicationDidFinishLaunching(_ notification: Notification) {
        configureMenu()
        let arguments = CommandLine.arguments
        if let index = arguments.firstIndex(of: "--verify-reopen"), index + 1 < arguments.count {
            DispatchQueue.main.async { self.verifyReopen(URL(fileURLWithPath: arguments[index + 1])) }; return
        }
        if let index = arguments.firstIndex(of: "--smoke-test"), index + 1 < arguments.count {
            DispatchQueue.main.async { self.smokeTest(URL(fileURLWithPath: arguments[index + 1])) }
            return
        }
        if let path = UserDefaults.standard.string(forKey: "lastWorkspace"), FileManager.default.fileExists(atPath: path) {
            do {
                let url = URL(fileURLWithPath: path)
                let local = isLocalWritable(url)
                try open(url, writable: local); return
            } catch { NSAlert(error: error).runModal() }
        }
        showWelcome()
    }
    public func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        do { for controller in controllers { controller.canvas.cancelOperation(nil); try controller.session.flush() }; return .terminateNow }
        catch { NSAlert(error: error).runModal(); return .terminateCancel }
    }
    public func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if !flag { if let first = controllers.first { first.showWindow(nil) } else { showWelcome() } }; return true
    }
    private var active: MacWindow? { controllers.first { $0.window?.isKeyWindow == true } ?? controllers.last }
    func open(_ url: URL, create: Bool = false, writable: Bool = false, preferences: UserDefaults = .standard) throws {
        if let existing = controllers.first(where: { $0.session.store.root == url.standardizedFileURL.resolvingSymlinksInPath() }) { existing.showWindow(nil); return }
        let store = try CanonicalStore(root: url, create: create, writable: writable)
        let session = try DesktopSession(store: store, preferences: preferences)
        let controller = MacWindow(session: session)
        controllers.append(controller)
        controller.closeHandler = { [weak self, weak controller] in self?.controllers.removeAll { $0 === controller } }
        welcome?.close(); welcome = nil
        controller.showWindow(nil); controller.window?.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true)
        if preferences === UserDefaults.standard { preferences.set(url.path, forKey: "lastWorkspace") }
    }
    private func showWelcome() {
        if let welcome { welcome.makeKeyAndOrderFront(nil); return }
        let window = NSWindow(contentRect: CGRect(x: 0, y: 0, width: 620, height: 430), styleMask: [.titled, .closable, .miniaturizable], backing: .buffered, defer: false)
        window.title = "墨笺"; window.isReleasedWhenClosed = false; window.appearance = NSAppearance(named: .darkAqua)
        let icon = NSImageView(); if let path = Bundle.main.path(forResource: "Mojian", ofType: "icns") { icon.image = NSImage(contentsOfFile: path) }; icon.imageScaling = .scaleProportionallyUpOrDown
        icon.widthAnchor.constraint(equalToConstant: 90).isActive = true; icon.heightAnchor.constraint(equalToConstant: 90).isActive = true
        let title = NSTextField(labelWithString: "墨笺 Mojian"); title.font = .systemFont(ofSize: 28, weight: .semibold)
        let detail = NSTextField(wrappingLabelWithString: "原生 macOS 笔记\n创建本地笔记库，或只读打开已有的墨笺文件夹。")
        detail.alignment = .center; detail.textColor = .secondaryLabelColor
        let create = NSButton(title: "创建本地笔记库…", target: self, action: #selector(createWorkspace(_:))); create.bezelStyle = .rounded; create.keyEquivalent = "\r"
        let open = NSButton(title: "打开已有笔记库…", target: self, action: #selector(openWorkspace(_:))); open.bezelStyle = .rounded
        let stack = NSStackView(views: [icon, title, detail, create, open]); stack.orientation = .vertical; stack.spacing = 18
        window.contentView!.addSubview(stack); stack.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([stack.centerXAnchor.constraint(equalTo: window.contentView!.centerXAnchor), stack.centerYAnchor.constraint(equalTo: window.contentView!.centerYAnchor), detail.widthAnchor.constraint(equalToConstant: 500)])
        welcome = window; window.center(); window.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true)
    }
    private func isLocalWritable(_ url: URL) -> Bool {
        FileManager.default.fileExists(atPath: url.appendingPathComponent(".mojian-macos-library").path) &&
            ![".stfolder", ".mojian-sync", ".mojian-native-workspace"].contains { FileManager.default.fileExists(atPath: url.appendingPathComponent($0).path) }
    }
    @objc func createWorkspace(_ sender: Any?) {
        let panel = NSOpenPanel(); panel.message = "选择空文件夹，创建独立的本地墨笺笔记库。"; panel.canChooseDirectories = true; panel.canChooseFiles = false; panel.canCreateDirectories = true
        if panel.runModal() == .OK, let url = panel.url { do { try open(url, create: true) } catch { NSAlert(error: error).runModal() } }
    }
    @objc func openWorkspace(_ sender: Any?) {
        let panel = NSOpenPanel(); panel.message = "打开墨笺文件夹。已有外部笔记库保持只读；Mac 创建的本地库可继续编辑。"; panel.canChooseDirectories = true; panel.canChooseFiles = false
        if panel.runModal() == .OK, let url = panel.url {
            let local = isLocalWritable(url)
            do { try open(url, writable: local) } catch { NSAlert(error: error).runModal() }
        }
    }
    @objc func showAbout(_ sender: Any?) { NSApp.orderFrontStandardAboutPanel(options: [.applicationName: "墨笺 Mojian", .applicationVersion: "macOS 0.1.0", .credits: NSAttributedString(string: "原生 AppKit 客户端 · 本地笔记与 canonical 格式\nHTTPS 同步及完整跨端功能仍在开发中。")]) }
    private func configureMenu() {
        let main = NSMenu()
        func menu(_ title: String) -> NSMenu { let item = NSMenuItem(); let menu = NSMenu(title: title); item.submenu = menu; main.addItem(item); return menu }
        @discardableResult func add(_ menu: NSMenu, _ title: String, _ selector: Selector, _ key: String = "", target: AnyObject? = nil, shift: Bool = false, tag: Int = 0, value: Any? = nil) -> NSMenuItem {
            let item = NSMenuItem(title: title, action: selector, keyEquivalent: key); item.target = target; item.tag = tag; item.representedObject = value
            if shift { item.keyEquivalentModifierMask = [.command, .shift] }; menu.addItem(item); return item
        }
        let app = menu("墨笺")
        add(app, "关于墨笺", #selector(showAbout(_:)), target: self); app.addItem(.separator())
        add(app, "隐藏墨笺", #selector(NSApplication.hide(_:)), "h", target: NSApp); add(app, "显示全部", #selector(NSApplication.unhideAllApplications(_:)), target: NSApp)
        app.addItem(.separator()); add(app, "退出墨笺", #selector(NSApplication.terminate(_:)), "q", target: NSApp)
        let file = menu("文件")
        add(file, "创建本地笔记库…", #selector(createWorkspace(_:)), "n", target: self, shift: true)
        add(file, "打开笔记库…", #selector(openWorkspace(_:)), "o", target: self)
        file.addItem(.separator())
        add(file, "新建笔记本…", #selector(MacWindow.newNotebook(_:)))
        add(file, "新建分区…", #selector(MacWindow.newSection(_:)))
        add(file, "新建子分区…", #selector(MacWindow.newSection(_:)), tag: 1)
        add(file, "新建页面…", #selector(MacWindow.newPage(_:)), "n")
        add(file, "重命名页面…", #selector(MacWindow.renamePage(_:)))
        add(file, "保存", #selector(MacWindow.saveNow(_:)), "s")
        file.addItem(.separator()); add(file, "导入笔记本 (.mjn)…", #selector(MacWindow.importNotebook(_:)))
        add(file, "导出笔记本 (.mjn)…", #selector(MacWindow.exportNotebook(_:)))
        add(file, "导出当前页 PDF…", #selector(MacWindow.exportPDF(_:)), "e")
        add(file, "打印…", #selector(MacWindow.printPage(_:)), "p")
        file.addItem(.separator()); add(file, "关闭窗口", #selector(NSWindow.performClose(_:)), "w")
        let edit = menu("编辑")
        add(edit, "撤销", #selector(MacWindow.performUndo(_:)), "z"); add(edit, "重做", #selector(MacWindow.performRedo(_:)), "z", shift: true)
        edit.addItem(.separator()); add(edit, "剪切", #selector(MacWindow.cut(_:)), "x"); add(edit, "复制", #selector(MacWindow.copy(_:)), "c"); add(edit, "粘贴", #selector(MacWindow.paste(_:)), "v"); add(edit, "全选", #selector(MacWindow.selectAll(_:)), "a")
        let insert = menu("插入"); add(insert, "文字…", #selector(MacWindow.insertTextBlock(_:))); add(insert, "图片…", #selector(MacWindow.insertImage(_:)))
        let draw = menu("绘图")
        for (i, tool) in PenTool.allCases.enumerated() { add(draw, tool.title, #selector(MacWindow.chooseTool(_:)), tag: i) }
        draw.addItem(.separator()); add(draw, "鼠标书写", #selector(MacWindow.toggleMouseWriting(_:)))
        let shapes = NSMenu(title: "图形种类"), shapeItem = NSMenuItem(title: "图形种类", action: nil, keyEquivalent: ""); shapeItem.submenu = shapes; draw.addItem(shapeItem)
        for (i, shape) in ShapeKind.allCases.enumerated() { add(shapes, shape.title, #selector(MacWindow.chooseShape(_:)), tag: i) }
        let colors = NSMenu(title: "笔色"), colorsItem = NSMenuItem(title: "笔色", action: nil, keyEquivalent: ""); colorsItem.submenu = colors; draw.addItem(colorsItem)
        for (i, title) in ["黑色 / 逻辑白色", "蓝色", "红色"].enumerated() { add(colors, title, #selector(MacWindow.chooseColor(_:)), tag: i) }
        let widths = NSMenu(title: "钢笔宽度"), widthItem = NSMenuItem(title: "钢笔宽度", action: nil, keyEquivalent: ""); widthItem.submenu = widths; draw.addItem(widthItem)
        for width in [0.2, 0.4, 0.6, 1.0, 1.5] { add(widths, "\(width) mm", #selector(MacWindow.chooseWidth(_:)), value: width) }
        let view = menu("显示")
        add(view, "放大", #selector(MacWindow.zoomIn(_:)), "+"); add(view, "缩小", #selector(MacWindow.zoomOut(_:)), "-")
        add(view, "实际大小", #selector(MacWindow.actualSize(_:)), "0"); add(view, "适合宽度", #selector(MacWindow.fitPage(_:)), "1")
        view.addItem(.separator()); add(view, "暗色页面", #selector(MacWindow.toggleTheme(_:)))
        let backgrounds = NSMenu(title: "当前页背景"), bg = NSMenuItem(title: "当前页背景", action: nil, keyEquivalent: ""); bg.submenu = backgrounds; view.addItem(bg)
        for format in Background.allCases { add(backgrounds, format.title, #selector(MacWindow.chooseBackground(_:)), value: format.rawValue) }
        let defaults = NSMenu(title: "新建页默认背景"), defaultItem = NSMenuItem(title: "新建页默认背景", action: nil, keyEquivalent: ""); defaultItem.submenu = defaults; view.addItem(defaultItem)
        for format in Background.allCases { add(defaults, format.title, #selector(MacWindow.chooseDefaultBackground(_:)), value: format.rawValue) }
        view.addItem(.separator()); add(view, "切换笔记本侧栏", #selector(MacWindow.toggleSidebar(_:))); add(view, "切换页面列表", #selector(MacWindow.togglePages(_:)))
        add(view, "进入 / 退出全屏", #selector(MacWindow.fullScreen(_:)), "f", shift: true)
        let window = menu("窗口"); add(window, "最小化", #selector(NSWindow.performMiniaturize(_:)), "m"); add(window, "缩放", #selector(NSWindow.performZoom(_:))); add(window, "全部置前", #selector(NSApplication.arrangeInFront(_:)), target: NSApp); NSApp.windowsMenu = window
        NSApp.mainMenu = main
    }
    private func verifyReopen(_ root: URL) {
        do {
            guard root.resolvingSymlinksInPath().path.hasPrefix(URL(fileURLWithPath: "/tmp/.agents").resolvingSymlinksInPath().path + "/") else { throw MojianError.invalid("重开验收必须使用隔离库") }
            let prefsName = "com.meta.mojian.macos.qa.\(UUID().uuidString)", prefs = UserDefaults(suiteName: prefsName)!
            defer { prefs.removePersistentDomain(forName: prefsName) }
            let workspace = root.appendingPathComponent("library")
            try open(workspace, writable: true, preferences: prefs)
            RunLoop.current.run(until: Date().addingTimeInterval(0.3))
            guard let controller = controllers.last, let d = controller.session.document, d.strokes.count == 1, d.texts.count == 1, controller.window?.isVisible == true else { throw MojianError.invalid("新进程未恢复笔迹或文字") }
            try JSON.object(["freshProcess": .bool(true), "nativeWindow": .bool(true), "strokes": .number(1), "texts": .number(1)]).data().write(to: root.appendingPathComponent("reopen-receipt.json"))
            controller.window?.close(); NSApp.terminate(nil)
        } catch { try? error.localizedDescription.write(to: root.appendingPathComponent("reopen-failure.txt"), atomically: true, encoding: .utf8); exit(1) }
    }
    private func smokeTest(_ root: URL) {
        do {
            let temp = URL(fileURLWithPath: "/tmp/.agents").resolvingSymlinksInPath().path + "/"
            guard root.resolvingSymlinksInPath().path.hasPrefix(temp) else { throw MojianError.invalid("隔离验收必须位于 /tmp/.agents") }
            let prefsName = "com.meta.mojian.macos.qa.\(UUID().uuidString)", prefs = UserDefaults(suiteName: prefsName)!
            defer { prefs.removePersistentDomain(forName: prefsName) }
            let workspace = root.appendingPathComponent("library")
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            try open(workspace, create: true, preferences: prefs)
            RunLoop.current.run(until: Date().addingTimeInterval(0.3))
            guard let controller = controllers.last, let page = controller.session.page else { throw MojianError.invalid("未创建原生窗口") }
            controller.window!.layoutIfNeeded(); controller.window!.contentView!.layoutSubtreeIfNeeded(); controller.canvas.mouseWriting = true
            let canvas = controller.canvas
            let diagnostics = "window=\(controller.window!.frame), key=\(controller.window!.isKeyWindow), canvas=\(canvas.frame), bounds=\(canvas.bounds), hit=\(String(describing: controller.window!.contentView!.hitTest(canvas.convert(CGPoint(x: 140, y: 140), to: controller.window!.contentView))))\n"
            try diagnostics.write(to: root.appendingPathComponent("input-diagnostic.txt"), atomically: true, encoding: .utf8)
            func send(_ type: NSEvent.EventType, _ point: CGPoint) throws {
                let p = canvas.convert(point, to: nil)
                guard let event = NSEvent.mouseEvent(with: type, location: p, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: controller.window!.windowNumber, context: nil, eventNumber: 1, clickCount: 1, pressure: 0.5) else { throw MojianError.invalid("无法创建原生输入事件") }
                controller.window!.sendEvent(event)
            }
            try send(.leftMouseDown, CGPoint(x: 140, y: 140)); try send(.leftMouseDragged, CGPoint(x: 240, y: 210)); try send(.leftMouseUp, CGPoint(x: 300, y: 180))
            guard controller.session.document?.strokes.count == 1 else { throw MojianError.invalid("原生鼠标书写未保存到模型") }
            let focused = controller.window!.isKeyWindow
            let actionTarget: Any? = focused ? nil : controller
            guard NSApp.sendAction(#selector(MacWindow.performUndo(_:)), to: actionTarget, from: nil) else { throw MojianError.invalid("原生菜单撤销响应链未连接") }; guard controller.session.document?.strokes.isEmpty == true else { throw MojianError.invalid("撤销未执行") }
            guard NSApp.sendAction(#selector(MacWindow.performRedo(_:)), to: actionTarget, from: nil) else { throw MojianError.invalid("原生菜单重做响应链未连接") }; guard controller.session.document?.strokes.count == 1 else { throw MojianError.invalid("重做未执行") }
            controller.session.mutate { $0.texts.append(.object(["id": .string("text-qa"), "text": .string("墨笺 macOS · 原生笔记\nCanonical local-first workspace"), "x": .number(80), "y": .number(250), "width": .number(440), "height": .number(120), "fontSize": .number(24)])) }
            canvas.rebind(); try controller.session.flush()
            let pageURL = try controller.session.store.checked("notebooks/\(controller.session.notebook!.id)/pages/\(page.id).json")
            let before = try Data(contentsOf: pageURL), index = try Data(contentsOf: workspace.appendingPathComponent("library.json"))
            canvas.zoom(0.5); canvas.zoom(64); canvas.zoom(1); controller.toggleTheme(nil); controller.toggleTheme(nil)
            try controller.session.flush()
            guard before == (try Data(contentsOf: pageURL)), index == (try Data(contentsOf: workspace.appendingPathComponent("library.json"))) else { throw MojianError.invalid("浏览修改了共享文件") }
            let reread = try controller.session.store.loadPage(notebook: controller.session.notebook!.id, page: page.id)
            guard reread.strokes.count == 1, reread.texts.count == 1 else { throw MojianError.invalid("持久化重读失败") }
            let pdf = try controller.pdfData(); try pdf.write(to: root.appendingPathComponent("native-output.pdf"))
            guard let view = controller.window?.contentView, let bitmap = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { throw MojianError.invalid("原生窗口截图失败") }
            view.cacheDisplay(in: view.bounds, to: bitmap)
            try bitmap.representation(using: .png, properties: [:])!.write(to: root.appendingPathComponent("native-window.png"))
            let readonly = try CanonicalStore(root: workspace)
            _ = try readonly.loadLibrary()
            do { _ = try readonly.writePage(reread); throw MojianError.invalid("只读笔记库允许写入") }
            catch MojianError.invalid(let text) where text == "当前笔记库为只读" { }
            let receipt: JSON = .object(["nativeWindow": .bool(controller.window!.isVisible), "focusedMenuResponderRoute": focused ? .bool(true) : .null, "mouseEventInk": .bool(true), "undoRedo": .bool(true), "diskReload": .bool(true), "viewOnlyBytesUnchanged": .bool(true), "pdfBytes": .number(Double(pdf.count)), "readOnlyExternal": .bool(readonly.readOnly)])
            try receipt.data().write(to: root.appendingPathComponent("receipt.json"))
            controller.window?.close(); NSApp.terminate(nil)
        } catch {
            let message = "FAILED: \(error.localizedDescription)\n"
            try? message.write(to: root.appendingPathComponent("failure.txt"), atomically: true, encoding: .utf8)
            fputs(message, stderr); exit(1)
        }
    }
}
