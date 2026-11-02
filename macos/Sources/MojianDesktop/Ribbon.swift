import AppKit
import MojianCore

/// Icon-only ribbon content. Only commands implemented on macOS are shown; shells never masquerade
/// as finished features (`windows-desktop.md`, `workspace-ui.md`).
extension MacWindow {
    func selectTab(_ t: RibbonTab) {
        tab = t; popover?.close()
        for (index, button) in tabButtons.enumerated() { button.isSelected = index == t.rawValue }
        rebuildRibbon()
    }
    func rebuildRibbon() {
        for view in ribbonStack.arrangedSubviews { view.removeFromSuperview() }
        keyed = keyed.filter { ["addSection", "addPage"].contains($0.key) }; presetButtons.removeAll()
        for view in items(for: tab) { ribbonStack.addArrangedSubview(view) }
        updateRibbonState()
    }
    private func command(_ key: String, _ icon: String, _ tip: String, tint: NSColor = Palette.text, _ action: @escaping (MacWindow, RibbonButton) -> Void) -> RibbonButton {
        let button = RibbonButton(icon: icon, tip: tip, tint: tint) { [weak self] b in if let self { action(self, b) } }
        keyed[key] = button; return button
    }
    private func dropdown(_ key: String, text: String, tip: String, width: CGFloat, _ action: @escaping (MacWindow, RibbonButton) -> Void) -> RibbonButton {
        let button = RibbonButton(text: text, tip: tip, size: NSSize(width: width, height: 42)) { [weak self] b in if let self { action(self, b) } }
        button.dropdown = true; keyed[key] = button; return button
    }
    private func items(for tab: RibbonTab) -> [NSView] {
        switch tab {
        case .home:
            return [
                command("paste", "clipboard_paste", "粘贴") { w, _ in w.paste(nil) },
                command("cut", "cut", "剪切") { w, _ in w.cut(nil) },
                command("copy", "copy", "复制") { w, _ in w.copy(nil) },
                ribbonDivider(),
                dropdown("fontSize", text: "16", tip: "字号", width: 56) { w, b in w.showFontSizes(b) },
                command("bold", "text_bold", "加粗") { w, _ in w.toggleText("bold") },
                command("italic", "text_italic", "倾斜") { w, _ in w.toggleText("italic") },
                command("underline", "text_underline", "下划线") { w, _ in w.toggleText("underline") },
                command("strikethrough", "text_strikethrough", "删除线") { w, _ in w.toggleText("strikethrough") },
                ribbonDivider(),
                command("alignLeft", "text_align_left", "左对齐") { w, _ in w.align("Left") },
                command("alignCenter", "text_align_center", "居中") { w, _ in w.align("Center") },
                command("alignRight", "text_align_right", "右对齐") { w, _ in w.align("Right") }
            ]
        case .insert:
            return [
                command("insertText", "text_font", "文字") { w, _ in w.insertTextBlock(nil) },
                command("insertImage", "image", "图片") { w, _ in w.insertImage(nil) }
            ]
        case .draw:
            var views: [NSView] = [
                command("undo", "arrow_undo", "撤销") { w, _ in w.performUndo(nil) },
                command("redo", "arrow_redo", "重做") { w, _ in w.performRedo(nil) },
                ribbonDivider(),
                // OneNote for Mac: the pointer tool returns mouse/trackpad to direct selection.
                command("mouseSelect", "cursor", "选择对象（鼠标与触控板）") { w, _ in w.session.mouseWriting = false; w.session.savePreferences(); w.updateRibbonState() }
            ]
            let groups: [[PenTool]] = [[.lasso], [.eraser, .highlighter, .pen], [.shape, .hand]]
            for (index, tools) in groups.enumerated() {
                if index > 0 { views.append(ribbonDivider()) }
                for tool in tools { for preset in session.presets where preset.tool == tool { views.append(presetButton(preset)) } }
                if index == 1 { views.append(command("addTool", "add", "添加") { w, b in w.showAddTool(b) }) }
            }
            views += [
                ribbonDivider(),
                command("background", "rule_lines", "设置背景格式") { w, b in w.showBackgrounds(b) },
                ribbonDivider(),
                command("clear", "delete_dismiss", "清纸") { w, _ in w.clearPage(nil) }
            ]
            return views
        case .view:
            return [
                command("fullPage", "full_screen_maximize", "整页视图") { w, _ in w.toggleFullPage(nil) },
                ribbonDivider(),
                command("zoomLock", "lock_closed", "锁定显示比例") { w, _ in w.session.settings.pinchLocked.toggle(); w.session.savePreferences(); w.updateRibbonState() },
                dropdown("zoom", text: "100%", tip: "显示比例", width: 70) { w, b in w.showZoomPresets(b) },
                command("zoomIn", "zoom_in", "放大") { w, _ in w.zoomIn(nil) },
                command("zoomOut", "zoom_out", "缩小") { w, _ in w.zoomOut(nil) },
                { let b = RibbonButton(text: "100%", tip: "100%", size: NSSize(width: 54, height: 42)) { [weak self] _ in self?.actualSize(nil) }; return b }(),
                command("pageWidth", "page_width", "页宽") { w, _ in w.fitPage(nil) },
                ribbonDivider(),
                command("viewBackground", "rule_lines", "基准线") { w, b in w.showBackgrounds(b) },
                command("paper", "document_page", "纸张大小") { w, b in w.showPapers(b) },
                command("titleHidden", "eye_off", "隐藏页面标题") { w, _ in w.toggleTitle(nil) },
                command("dark", "weather_moon", "暗色模式（所有页面）") { w, _ in w.toggleTheme(nil) }
            ]
        }
    }

    // MARK: Tool presets
    private func presetButton(_ preset: ToolPreset) -> RibbonButton {
        let tint = [.pen, .highlighter].contains(preset.tool) ? Palette.ink(preset.color) : Palette.text
        let button = RibbonButton(icon: preset.icon, tip: preset.title + (preset.hasFlyout ? "（再次点击设置）" : ""), tint: tint) { [weak self] b in self?.pressPreset(preset.id, b) }
        button.showsCaret = preset.hasFlyout; presetButtons[preset.id] = button; return button
    }
    /// Two-click contract: first click selects, a click on the selected tool toggles its flyout, and a
    /// click that dismissed a transient popover never reopens it.
    private func pressPreset(_ id: String, _ button: RibbonButton) {
        guard let preset = session.presets.first(where: { $0.id == id }) else { return }
        if let (closed, at) = lastPopoverClose, closed == id, Date().timeIntervalSince(at) < 0.35 { lastPopoverClose = nil; return }
        if popover?.isShown == true { let open = popoverPreset; popover?.close(); if open == id { return } }
        if session.activePreset == id && session.mouseWriting {
            if preset.hasFlyout { showFlyout(preset, from: button) }
        } else {
            session.mouseWriting = true; session.apply(preset)
        }
    }
    private func showAddTool(_ button: RibbonButton) {
        let menu = NSMenu(); menu.autoenablesItems = false
        for tool in [PenTool.pen, .highlighter] {
            let entry = NSMenuItem(title: tool.title, action: #selector(addPresetItem(_:)), keyEquivalent: ""); entry.target = self; entry.tag = tool == .pen ? 0 : 1
            entry.image = FluentIcon.image(tool == .pen ? "pen" : "highlight", size: 16); menu.addItem(entry)
        }
        menu.popUp(positioning: nil, at: CGPoint(x: 0, y: button.bounds.height + 2), in: button)
    }
    @objc func addPresetItem(_ sender: NSMenuItem) { session.addPreset(sender.tag == 0 ? .pen : .highlighter); rebuildRibbon() }
    private func showFlyout(_ preset: ToolPreset, from button: RibbonButton) {
        let stack = NSStackView(); stack.orientation = .vertical; stack.alignment = .leading; stack.spacing = 12
        stack.edgeInsets = NSEdgeInsets(top: 14, left: 16, bottom: 14, right: 16)
        let heading = NSTextField(labelWithString: preset.title); heading.font = .systemFont(ofSize: 13, weight: .semibold); stack.addArrangedSubview(heading)
        if [.pen, .highlighter, .shape].contains(preset.tool) {
            let swatches = (0..<3).map { color -> RibbonButton in
                let b = RibbonButton(tip: ["黑色", "蓝色", "红色"][color], size: NSSize(width: 34, height: 30)) { [weak self] _ in self?.editPreset(preset.id) { $0.color = color } }
                b.icon = FluentIcon.swatch(Palette.swatch(color), ring: color == 0); b.isSelected = preset.color == color; return b
            }
            let row = NSStackView(views: swatches); row.spacing = 4; stack.addArrangedSubview(row)
        }
        if preset.tool == .shape {
            let shapes = NSPopUpButton(); for kind in ShapeKind.allCases { shapes.addItem(withTitle: kind.title) }
            shapes.selectItem(at: ShapeKind.allCases.firstIndex(of: preset.shape) ?? 0)
            shapes.target = self; shapes.action = #selector(flyoutShape(_:)); stack.addArrangedSubview(labeled("图形", shapes))
        }
        let range = preset.widthRange
        stack.addArrangedSubview(slider(preset.tool == .eraser ? "半径" : "粗细", value: preset.widthMM, range: range, unit: "mm", tag: 0))
        if preset.tool == .highlighter { stack.addArrangedSubview(slider("不透明度", value: preset.opacity, range: 10...80, unit: "%", tag: 1)) }
        let controller = NSViewController(); controller.view = stack
        let p = NSPopover(); p.behavior = .transient; p.appearance = NSAppearance(named: .darkAqua); p.contentViewController = controller; p.delegate = self
        popover = p; popoverPreset = preset.id
        p.show(relativeTo: button.bounds, of: button, preferredEdge: .maxY)
    }
    private func labeled(_ title: String, _ control: NSView) -> NSView {
        let label = NSTextField(labelWithString: title); label.textColor = Palette.secondary; label.widthAnchor.constraint(equalToConstant: 64).isActive = true
        let row = NSStackView(views: [label, control]); row.spacing = 8; return row
    }
    private func slider(_ title: String, value: Double, range: ClosedRange<Double>, unit: String, tag: Int) -> NSView {
        let control = NSSlider(value: min(range.upperBound, max(range.lowerBound, value)), minValue: range.lowerBound, maxValue: range.upperBound, target: self, action: #selector(flyoutSlider(_:)))
        control.tag = tag; control.isContinuous = true; control.widthAnchor.constraint(equalToConstant: 170).isActive = true
        let readout = NSTextField(labelWithString: format(value, unit)); readout.font = .monospacedDigitSystemFont(ofSize: 11, weight: .regular)
        readout.textColor = Palette.secondary; readout.identifier = .init("readout-\(tag)"); readout.widthAnchor.constraint(equalToConstant: 58).isActive = true
        let row = NSStackView(views: [labeled(title, control), readout]); row.spacing = 6; return row
    }
    private func format(_ value: Double, _ unit: String) -> String { unit == "%" ? "\(Int(value.rounded()))%" : String(format: value < 1 ? "%.2f mm" : "%.1f mm", value) }
    @objc private func flyoutSlider(_ sender: NSSlider) {
        guard let id = popoverPreset else { return }
        let value = sender.doubleValue
        editPreset(id, rebuild: false) { if sender.tag == 0 { $0.widthMM = value } else { $0.opacity = value } }
        let readout = sender.superview?.superview?.subviews.compactMap { $0 as? NSTextField }.first { $0.identifier?.rawValue == "readout-\(sender.tag)" }
        readout?.stringValue = format(value, sender.tag == 0 ? "mm" : "%")
    }
    @objc private func flyoutShape(_ sender: NSPopUpButton) {
        guard let id = popoverPreset else { return }
        let kind = ShapeKind.allCases[max(0, sender.indexOfSelectedItem)]
        editPreset(id, rebuild: false) { $0.shape = kind }
    }
    /// Colors change the ribbon icon tint, so the ribbon and the open flyout are rebuilt together.
    private func editPreset(_ id: String, rebuild: Bool = true, _ change: (inout ToolPreset) -> Void) {
        guard var preset = session.presets.first(where: { $0.id == id }) else { return }
        change(&preset); session.update(preset)
        guard rebuild else { return }
        popover?.close(); lastPopoverClose = nil; rebuildRibbon()
        if let button = presetButtons[id] { showFlyout(preset, from: button) }
    }

    // MARK: Menus
    func showBackgrounds(_ button: RibbonButton) {
        let menu = NSMenu(), current = session.displayPage?.view["backgroundFormat"].string
        for format in Background.allCases {
            let entry = NSMenuItem(title: format.title, action: #selector(chooseBackground(_:)), keyEquivalent: ""); entry.target = self
            entry.representedObject = format.rawValue; entry.state = format.rawValue == current ? .on : .off; menu.addItem(entry)
            if [.ruledClean, .twoColumnWide, .gridWide].contains(format) { menu.addItem(.separator()) }
        }
        menu.addItem(.separator())
        let defaults = NSMenuItem(title: "新建页默认背景", action: nil, keyEquivalent: ""), sub = NSMenu()
        for format in Background.allCases {
            let entry = NSMenuItem(title: format.title, action: #selector(chooseDefaultBackground(_:)), keyEquivalent: ""); entry.target = self; entry.representedObject = format.rawValue; sub.addItem(entry)
        }
        defaults.submenu = sub; menu.addItem(defaults)
        menu.popUp(positioning: nil, at: CGPoint(x: 0, y: button.bounds.height + 2), in: button)
    }
    private func showPapers(_ button: RibbonButton) {
        let menu = NSMenu()
        for (title, value) in [("无界纸张", "BOUNDLESS"), ("A4 连续纸张", "A4")] {
            let entry = NSMenuItem(title: title, action: #selector(choosePaper(_:)), keyEquivalent: ""); entry.target = self; entry.representedObject = value; menu.addItem(entry)
        }
        menu.popUp(positioning: nil, at: CGPoint(x: 0, y: button.bounds.height + 2), in: button)
    }
    private func showZoomPresets(_ button: RibbonButton) {
        let menu = NSMenu(), current = Int((canvas.viewport.scale * 100).rounded())
        for percent in [50, 75, 100, 125, 150, 200, 400, 800, 1600, 3200, 6400] {
            let entry = NSMenuItem(title: "\(percent)%", action: #selector(chooseZoom(_:)), keyEquivalent: ""); entry.target = self; entry.tag = percent
            entry.state = percent == current ? .on : .off; menu.addItem(entry)
        }
        menu.popUp(positioning: nil, at: CGPoint(x: 0, y: button.bounds.height + 2), in: button)
    }
    @objc func chooseZoom(_ sender: NSMenuItem) { canvas.zoom(Double(sender.tag) / 100) }
    private func showFontSizes(_ button: RibbonButton) {
        let menu = NSMenu(), current = selectedTexts().first?["fontSize"].number(16)
        for size in [10, 11, 12, 14, 16, 18, 20, 24, 28, 32, 40, 48, 72] {
            let entry = NSMenuItem(title: "\(size)", action: #selector(chooseFontSize(_:)), keyEquivalent: ""); entry.target = self; entry.tag = size
            entry.state = Double(size) == current ? .on : .off; menu.addItem(entry)
        }
        menu.popUp(positioning: nil, at: CGPoint(x: 0, y: button.bounds.height + 2), in: button)
    }

    // MARK: Text formatting on selected text boxes
    private func selectedTexts() -> [JSON] { session.document?.texts.filter { canvas.selected.contains($0["id"].string) } ?? [] }
    @objc func chooseFontSize(_ sender: NSMenuItem) {
        let size = Double(sender.tag)
        session.formatTexts(canvas.selected) { item in
            // Keep the box proportional so enlarged text is not clipped by its stored frame.
            let ratio = size / max(1, item["fontSize"].number(16))
            item["fontSize"] = .number(size); item["height"] = .number(max(item["height"].number(60), item["height"].number(60) * ratio))
        }
        canvas.rebind()
    }
    func toggleText(_ key: String) {
        let enable = !selectedTexts().allSatisfy { $0[key].bool() }
        session.formatTexts(canvas.selected) { $0[key] = .bool(enable) }; canvas.rebind()
    }
    func align(_ value: String) { session.formatTexts(canvas.selected) { $0["alignment"] = .string(value) }; canvas.rebind() }

    // MARK: State
    func updateRibbonState() {
        let editable = session.canEdit, texts = selectedTexts()
        for (id, button) in presetButtons { button.isSelected = id == session.activePreset && session.mouseWriting }
        keyed["mouseSelect"]?.isSelected = !session.mouseWriting
        keyed["undo"]?.isEnabled = editable && session.canUndo; keyed["redo"]?.isEnabled = editable && session.canRedo
        for key in ["paste", "insertText", "insertImage", "background", "viewBackground", "clear", "titleHidden"] { keyed[key]?.isEnabled = editable }
        keyed["paper"]?.isEnabled = editable && session.displayPage?.paper != "PDF"
        keyed["cut"]?.isEnabled = editable && !canvas.selected.isEmpty; keyed["copy"]?.isEnabled = !canvas.selected.isEmpty
        for key in ["fontSize", "bold", "italic", "underline", "strikethrough", "alignLeft", "alignCenter", "alignRight"] { keyed[key]?.isEnabled = editable && !texts.isEmpty }
        for key in ["bold", "italic", "underline", "strikethrough"] { keyed[key]?.isSelected = !texts.isEmpty && texts.allSatisfy { $0[key].bool() } }
        let alignment = texts.first?["alignment"].string ?? ""
        keyed["alignLeft"]?.isSelected = !texts.isEmpty && (alignment.isEmpty || alignment == "Left")
        keyed["alignCenter"]?.isSelected = alignment == "Center"; keyed["alignRight"]?.isSelected = alignment == "Right"
        keyed["fontSize"]?.text = texts.first.map { "\(Int($0["fontSize"].number(16)))" } ?? "16"
        keyed["zoomLock"]?.isSelected = session.settings.pinchLocked
        keyed["titleHidden"]?.isSelected = !(session.displayPage?.view["pageTitleVisible"].bool(true) ?? true)
        keyed["dark"]?.isSelected = session.dark
        keyed["addPage"]?.isEnabled = !session.store.readOnly && root != nil
        keyed["addSection"]?.isEnabled = !session.store.readOnly
        updateZoomLabel()
    }
    func updateZoomLabel() { keyed["zoom"]?.text = "\(Int((canvas.viewport.scale * 100).rounded()))%" }
}
