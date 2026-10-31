import AppKit
import PDFKit
import UniformTypeIdentifiers
import MojianCore

@MainActor final class MacWindow: NSWindowController, NSWindowDelegate, NSOutlineViewDataSource, NSOutlineViewDelegate, NSTableViewDataSource, NSTableViewDelegate, NSSearchFieldDelegate, NSMenuItemValidation {
    let session: DesktopSession
    let canvas: NoteCanvas
    let outline = NSOutlineView()
    let pages = NSTableView()
    let search = NSSearchField()
    let status = NSTextField(labelWithString: "")
    let zoomLabel = NSTextField(labelWithString: "100%")
    private let toolPicker: NSSegmentedControl
    private let split = NSSplitViewController()
    private let pageMode = NSButton(checkboxWithTitle: "递归页面", target: nil, action: nil)
    private var visiblePages = [(Notebook, Section, Page)]()
    private var refreshing = false
    private var clipboard: (PageDocument, [String: Data], Int)?
    var closeHandler: (() -> Void)?
    init(session: DesktopSession) {
        self.session = session; canvas = NoteCanvas(session: session)
        toolPicker = NSSegmentedControl(labels: ["钢笔", "荧光笔", "橡皮", "套索", "抓手", "图形"], trackingMode: .selectOne, target: nil, action: nil)
        let window = NSWindow(contentRect: CGRect(x: 0, y: 0, width: 1280, height: 850), styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        window.minSize = CGSize(width: 820, height: 520); window.title = "墨笺"; window.titlebarAppearsTransparent = true
        window.tabbingMode = .disallowed; window.appearance = NSAppearance(named: .darkAqua)
        super.init(window: window); window.delegate = self
        if !CommandLine.arguments.contains("--smoke-test") && !CommandLine.arguments.contains("--verify-reopen") { window.setFrameAutosaveName("MojianMacMainWindow") }; window.center()
        buildContent(); buildToolbar()
        session.changed = { [weak self] in self?.refresh() }
        session.onError = { [weak self] in self?.showError($0) }
        canvas.viewportChanged = { [weak self] in self?.updateStatus() }
        canvas.editText = { [weak self] item, point in self?.editText(item, at: point) }
        refresh(reloadTree: true)
        window.setContentSize(CGSize(width: 1280, height: 800))
        window.contentView?.layoutSubtreeIfNeeded()
        split.splitView.setPosition(196, ofDividerAt: 0)
        split.splitView.setPosition(416, ofDividerAt: 1)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }
    private func scroll(_ content: NSView) -> NSScrollView {
        let view = NSScrollView(); view.documentView = content; view.hasVerticalScroller = true; view.autohidesScrollers = true; view.drawsBackground = false
        return view
    }
    private func buildContent() {
        let column = NSTableColumn(identifier: .init("title")); column.title = "笔记本与分区"
        outline.addTableColumn(column); outline.outlineTableColumn = column; outline.headerView = nil; outline.rowHeight = 30
        outline.delegate = self; outline.dataSource = self; outline.style = .sourceList
        outline.setAccessibilityLabel("笔记本与分区"); outline.indentationPerLevel = 16
        let sidebar = NSViewController(); sidebar.view = scroll(outline)
        let sidebarItem = NSSplitViewItem(sidebarWithViewController: sidebar); sidebarItem.minimumThickness = 170; sidebarItem.maximumThickness = 320; sidebarItem.canCollapse = true
        split.addSplitViewItem(sidebarItem)
        let pColumn = NSTableColumn(identifier: .init("page")); pages.addTableColumn(pColumn)
        pages.headerView = nil; pages.rowHeight = 32; pages.delegate = self; pages.dataSource = self
        pages.setAccessibilityLabel("页面"); pages.target = self; pages.doubleAction = #selector(renamePage(_:))
        search.appearance = NSAppearance(named: .darkAqua); search.textColor = NSColor(hex: "#F4F4F4"); search.backgroundColor = NSColor(hex: "#343434"); search.drawsBackground = true
        search.placeholderString = "搜索页面标题"; search.delegate = self; search.setAccessibilityLabel("搜索页面标题")
        pageMode.target = self; pageMode.action = #selector(changePageMode(_:))
        let pageStack = NSStackView(views: [search, pageMode, scroll(pages)])
        pageStack.wantsLayer = true; pageStack.layer?.backgroundColor = NSColor(hex: "#242424").cgColor
        pageStack.orientation = .vertical; pageStack.alignment = .leading; pageStack.spacing = 10; pageStack.edgeInsets = NSEdgeInsets(top: 12, left: 10, bottom: 0, right: 10)
        for v in pageStack.arrangedSubviews { v.widthAnchor.constraint(equalTo: pageStack.widthAnchor, constant: -20).isActive = true }
        let pageController = NSViewController(); pageController.view = pageStack
        let pageItem = NSSplitViewItem(viewController: pageController); pageItem.minimumThickness = 180; pageItem.maximumThickness = 320; pageItem.canCollapse = true
        split.addSplitViewItem(pageItem)
        let canvasHost = NSViewController(); canvasHost.view = NSView()
        canvasHost.view.addSubview(canvas); canvas.translatesAutoresizingMaskIntoConstraints = false
        let footer = NSStackView(views: [zoomLabel, status]); footer.orientation = .horizontal; footer.spacing = 12; footer.alignment = .centerY
        status.font = .systemFont(ofSize: 11); status.textColor = .secondaryLabelColor; zoomLabel.font = .monospacedDigitSystemFont(ofSize: 11, weight: .regular)
        canvasHost.view.addSubview(footer); footer.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            canvas.leadingAnchor.constraint(equalTo: canvasHost.view.leadingAnchor), canvas.trailingAnchor.constraint(equalTo: canvasHost.view.trailingAnchor),
            canvas.topAnchor.constraint(equalTo: canvasHost.view.topAnchor), canvas.bottomAnchor.constraint(equalTo: canvasHost.view.bottomAnchor),
            footer.trailingAnchor.constraint(equalTo: canvasHost.view.trailingAnchor, constant: -10), footer.bottomAnchor.constraint(equalTo: canvasHost.view.bottomAnchor, constant: -6)
        ])
        split.addSplitViewItem(NSSplitViewItem(viewController: canvasHost)); window?.contentViewController = split
    }
    private func buildToolbar() {
        let toolbar = NSToolbar(identifier: "MojianMacToolbar"); toolbar.displayMode = .iconOnly
        let provider = ToolbarProvider(owner: self)
        toolbar.delegate = provider; toolbarProvider = provider
        window?.toolbar = toolbar; window?.toolbarStyle = .unifiedCompact
        toolPicker.target = self; toolPicker.action = #selector(changeTool(_:)); toolPicker.setAccessibilityLabel("书写工具")
    }
    private var toolbarProvider: ToolbarProvider?
    func toolbarItem(_ id: String) -> NSToolbarItem {
        let item = NSToolbarItem(itemIdentifier: .init(id))
        if id == "tools" { item.label = "书写工具"; item.view = toolPicker; return item }
        let defs: [String: (String, String, Selector)] = [
            "new": ("新建页面", "square.and.pencil", #selector(newPage(_:))), "undo": ("撤销", "arrow.uturn.backward", #selector(performUndo(_:))),
            "redo": ("重做", "arrow.uturn.forward", #selector(performRedo(_:))), "text": ("插入文字", "textformat", #selector(insertTextBlock(_:))),
            "image": ("插入图片", "photo", #selector(insertImage(_:))), "fit": ("适合宽度", "arrow.left.and.right", #selector(fitPage(_:)))
        ]
        if let d = defs[id] { item.label = d.0; item.toolTip = d.0; item.image = NSImage(systemSymbolName: d.1, accessibilityDescription: d.0); item.target = self; item.action = d.2 }
        return item
    }
    func refresh(reloadTree: Bool = false) {
        refreshing = true; defer { refreshing = false }
        if reloadTree || outline.numberOfRows == 0 {
            outline.reloadData(); for n in session.notebooks { outline.expandItem(n, expandChildren: true) }
        }
        if let s = session.section { let row = outline.row(forItem: s); if row >= 0 { outline.selectRowIndexes(IndexSet(integer: row), byExtendingSelection: false) } }
        reloadPages()
        toolPicker.selectedSegment = PenTool.allCases.firstIndex(of: session.settings.tool) ?? 0
        window?.title = (session.page?.title ?? "墨笺") + (session.store.readOnly ? " — 只读" : " — 墨笺")
        window?.representedURL = session.store.root; window?.isDocumentEdited = session.dirty
        updateStatus(); canvas.needsDisplay = true
    }
    private func reloadPages() {
        let query = search.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        if !query.isEmpty { visiblePages = session.notebooks.flatMap { n in n.allSections.flatMap { s in s.pages.filter { $0.title.localizedCaseInsensitiveContains(query) }.map { (n, s, $0) } } } }
        else if let n = session.notebook, let s = session.section {
            visiblePages = (pageMode.state == .on ? s.all : [s]).flatMap { section in section.pages.map { (n, section, $0) } }
        } else { visiblePages = [] }
        pages.reloadData()
        if let id = session.page?.id, let row = visiblePages.firstIndex(where: { $0.2.id == id }) { pages.selectRowIndexes(IndexSet(integer: row), byExtendingSelection: false) }
    }
    func updateStatus() {
        zoomLabel.stringValue = "\(Int((canvas.viewport.scale * 100).rounded()))%"
        status.stringValue = session.status; status.textColor = session.saveError == nil ? .secondaryLabelColor : .systemRed
    }
    func outlineView(_ outlineView: NSOutlineView, numberOfChildrenOfItem item: Any?) -> Int {
        if let n = item as? Notebook { return n.sections.count }
        if let s = item as? Section { return s.children.count }; return session.notebooks.count
    }
    func outlineView(_ outlineView: NSOutlineView, child index: Int, ofItem item: Any?) -> Any {
        if let n = item as? Notebook { return n.sections[index] }
        if let s = item as? Section { return s.children[index] }; return session.notebooks[index]
    }
    func outlineView(_ outlineView: NSOutlineView, isItemExpandable item: Any) -> Bool {
        if let n = item as? Notebook { return !n.sections.isEmpty }; return (item as? Section).map { !$0.children.isEmpty } ?? false
    }
    func outlineView(_ outlineView: NSOutlineView, viewFor tableColumn: NSTableColumn?, item: Any) -> NSView? {
        let text = (item as? Notebook)?.title ?? (item as? Section)?.title ?? ""
        return rowLabel(text, symbol: item is Notebook ? "book.closed" : "folder")
    }
    private func rowLabel(_ text: String, symbol: String? = nil) -> NSView {
        let label = NSTextField(labelWithString: text); label.lineBreakMode = .byTruncatingTail; label.font = .systemFont(ofSize: 13)
        let view = NSStackView(); view.orientation = .horizontal; view.spacing = 7
        if let symbol, let image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil) { let imageView = NSImageView(image: image); imageView.contentTintColor = NSColor(hex: "#DAB2E9"); view.addArrangedSubview(imageView) }
        view.addArrangedSubview(label); return view
    }
    func outlineViewSelectionDidChange(_ notification: Notification) {
        guard !refreshing, outline.selectedRow >= 0 else { return }
        let item = outline.item(atRow: outline.selectedRow)
        do {
            if let n = item as? Notebook { try session.select(n, nil, nil) }
            else if let s = item as? Section, let n = session.notebooks.first(where: { $0.allSections.contains { $0 === s } }) { try session.select(n, s, s.pages.first) }
            canvas.rebind(resetViewport: true); refresh()
        } catch { showError(error); refresh() }
    }
    func numberOfRows(in tableView: NSTableView) -> Int { visiblePages.count }
    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? { rowLabel(visiblePages[row].2.title) }
    func tableViewSelectionDidChange(_ notification: Notification) {
        guard !refreshing, pages.selectedRow >= 0, pages.selectedRow < visiblePages.count else { return }
        let (n, s, p) = visiblePages[pages.selectedRow]
        do { try session.select(n, s, p); canvas.rebind(resetViewport: true); refresh() } catch { showError(error); refresh() }
    }
    func controlTextDidChange(_ obj: Notification) { refreshing = true; reloadPages(); refreshing = false }
    @objc func changePageMode(_ sender: Any?) { refreshing = true; reloadPages(); refreshing = false }
    @objc func changeTool(_ sender: Any?) { session.choose(PenTool.allCases[max(0, toolPicker.selectedSegment)]) }
    @objc func chooseTool(_ sender: NSMenuItem) { session.choose(PenTool.allCases[sender.tag]) }
    @objc func toggleMouseWriting(_ sender: Any?) { canvas.mouseWriting.toggle(); canvas.needsDisplay = true }
    @objc func performUndo(_ sender: Any?) { session.undo(); canvas.rebind() }
    @objc func performRedo(_ sender: Any?) { session.redo(); canvas.rebind() }
    @objc func fitPage(_ sender: Any?) { canvas.fitPage() }
    @objc func zoomIn(_ sender: Any?) { canvas.zoom(canvas.viewport.scale * 1.25) }
    @objc func zoomOut(_ sender: Any?) { canvas.zoom(canvas.viewport.scale / 1.25) }
    @objc func actualSize(_ sender: Any?) { canvas.zoom(1) }
    @objc func toggleTheme(_ sender: Any?) { session.dark.toggle(); session.savePreferences(); canvas.needsDisplay = true }
    @objc func toggleSidebar(_ sender: Any?) { split.splitViewItems[0].isCollapsed.toggle() }
    @objc func togglePages(_ sender: Any?) { split.splitViewItems[1].isCollapsed.toggle() }
    @objc func fullScreen(_ sender: Any?) { window?.toggleFullScreen(sender) }
    @objc func chooseBackground(_ sender: NSMenuItem) {
        do { try session.setBackground(sender.representedObject as? String ?? "BLANK") } catch { showError(error) }
    }
    @objc func chooseDefaultBackground(_ sender: NSMenuItem) { session.defaultBackground = sender.representedObject as? String ?? "GRID_STANDARD"; session.savePreferences() }
    @objc func chooseShape(_ sender: NSMenuItem) { session.settings.shape = ShapeKind.allCases[sender.tag]; session.choose(.shape) }
    @objc func chooseColor(_ sender: NSMenuItem) { session.settings.color = sender.tag; session.savePreferences() }
    @objc func chooseWidth(_ sender: NSMenuItem) { session.settings.widthMM = (sender.representedObject as? Double) ?? 0.4; session.savePreferences() }
    @objc func newPage(_ sender: Any?) {
        let alert = NSAlert(); alert.messageText = "新建页面"; alert.addButton(withTitle: "创建"); alert.addButton(withTitle: "取消")
        let title = NSTextField(string: "无标题页"), paper = NSPopUpButton(); paper.addItems(withTitles: ["无界纸张", "A4 连续纸张"])
        let stack = NSStackView(views: [title, paper]); stack.orientation = .vertical; stack.frame = CGRect(x: 0, y: 0, width: 300, height: 64); title.widthAnchor.constraint(equalToConstant: 300).isActive = true; alert.accessoryView = stack
        if alert.runModal() == .alertFirstButtonReturn {
            do { try session.addPage(title: title.stringValue, paper: paper.indexOfSelectedItem == 1 ? "A4" : "BOUNDLESS"); canvas.rebind(resetViewport: true); refresh(reloadTree: true) } catch { showError(error) }
        }
    }
    private func prompt(_ message: String, value: String = "") -> String? {
        let alert = NSAlert(); alert.messageText = message; alert.addButton(withTitle: "确定"); alert.addButton(withTitle: "取消")
        let field = NSTextField(string: value); field.frame = CGRect(x: 0, y: 0, width: 300, height: 25); alert.accessoryView = field; alert.window.initialFirstResponder = field
        guard alert.runModal() == .alertFirstButtonReturn else { return nil }
        let result = field.stringValue.trimmingCharacters(in: .whitespacesAndNewlines); return result.isEmpty ? nil : result
    }
    @objc func newNotebook(_ sender: Any?) {
        guard let title = prompt("新建笔记本") else { return }
        do { try session.addNotebook(title: title); canvas.rebind(resetViewport: true); refresh(reloadTree: true) } catch { showError(error) }
    }
    @objc func newSection(_ sender: NSMenuItem) {
        guard let title = prompt(sender.tag == 1 ? "新建子分区" : "新建分区") else { return }
        do { try session.addSection(title: title, nested: sender.tag == 1); canvas.rebind(resetViewport: true); refresh(reloadTree: true) } catch { showError(error) }
    }
    @objc func renamePage(_ sender: Any?) {
        guard let page = session.page, let title = prompt("重命名页面", value: page.title) else { return }
        do { try session.renamePage(title); refresh() } catch { showError(error) }
    }
    @objc func insertTextBlock(_ sender: Any?) { editText(nil, at: canvas.viewport.document(CGPoint(x: 60, y: 100))) }
    func editText(_ item: JSON?, at point: CGPoint) {
        guard session.canEdit else { return }
        let alert = NSAlert(); alert.messageText = item == nil ? "插入文字" : "编辑文字"; alert.addButton(withTitle: "保存"); alert.addButton(withTitle: "取消")
        let editor = NSTextView(frame: CGRect(x: 0, y: 0, width: 460, height: 180)); editor.isRichText = false; editor.font = .systemFont(ofSize: 16); editor.string = item?["text"].string ?? ""
        let scroller = scroll(editor); scroller.frame = editor.frame; alert.accessoryView = scroller; alert.window.initialFirstResponder = editor
        guard alert.runModal() == .alertFirstButtonReturn, !editor.string.isEmpty else { return }
        var value = item ?? .object(["id": .string(JSON.id("text")), "x": .number(point.x), "y": .number(point.y), "width": .number(400), "height": .number(200), "fontSize": .number(16)])
        value["text"] = .string(editor.string)
        session.mutate { d in if let index = d.texts.firstIndex(where: { $0["id"] == value["id"] }) { d.texts[index] = value } else { d.texts.append(value) } }; canvas.rebind()
    }
    @objc func insertImage(_ sender: Any?) {
        let panel = NSOpenPanel(); panel.allowedContentTypes = [.png, .jpeg, .tiff, .heic]; panel.canChooseDirectories = false
        if panel.runModal() == .OK, let url = panel.url { do { try insertImageData(Data(contentsOf: url), title: url.lastPathComponent) } catch { showError(error) } }
    }
    func insertImageData(_ data: Data, title: String) throws {
        guard session.canEdit, let d = session.document, let image = NSImage(data: data), let tiff = image.tiffRepresentation, let bitmap = NSBitmapImageRep(data: tiff), let png = bitmap.representation(using: .png, properties: [:]) else { throw MojianError.invalid("图片格式无效或当前页不可编辑") }
        let name = JSON.id("image") + ".png", point = canvas.viewport.document(CGPoint(x: 60, y: 100)), width = min(480, Double(bitmap.pixelsWide)), height = width * Double(bitmap.pixelsHigh) / Double(max(1, bitmap.pixelsWide))
        try session.store.writeAsset(png, notebook: d.notebookID, page: d.pageID, name: name)
        let value: JSON = .object(["id": .string(JSON.id("object")), "kind": .number(2), "x": .number(point.x), "y": .number(point.y), "width": .number(width), "height": .number(height), "assetName": .string(name), "displayName": .string(title), "viewInverted": .bool(false)])
        session.mutate { $0.objects.append(value) }; canvas.rebind()
    }
    @objc func importNotebook(_ sender: Any?) {
        let panel = NSOpenPanel(); panel.allowedContentTypes = [UTType(filenameExtension: "mjn") ?? .zip, .zip]
        if panel.runModal() == .OK, let url = panel.url {
            do { try session.importNotebook(url); canvas.rebind(resetViewport: true); refresh(reloadTree: true) } catch { showError(error) }
        }
    }
    @objc func exportNotebook(_ sender: Any?) {
        let panel = NSSavePanel(); panel.allowedContentTypes = [UTType(filenameExtension: "mjn") ?? .zip]; panel.nameFieldStringValue = (session.notebook?.title ?? "笔记本") + ".mjn"
        if panel.runModal() == .OK, let url = panel.url {
            do { let n = try session.exchangeNotebook(); let store = try WorkspaceStore(root: session.store.root); try NotebookArchive.export(n, store: store, to: url) } catch { showError(error) }
        }
    }
    @objc func exportPDF(_ sender: Any?) {
        let panel = NSSavePanel(); panel.allowedContentTypes = [.pdf]; panel.nameFieldStringValue = (session.page?.title ?? "笔记") + ".pdf"
        if panel.runModal() == .OK, let url = panel.url { do { try pdfData().write(to: url, options: .atomic) } catch { showError(error) } }
    }
    func pdfData(printing: Bool = false) throws -> Data {
        try session.flush()
        guard let page = session.displayPage, let d = session.document else { throw MojianError.invalid("请先选择一个页面") }
        let result = NSMutableData(), consumer = CGDataConsumer(data: result)!
        guard let c = CGContext(consumer: consumer, mediaBox: nil, nil) else { throw MojianError.invalid("无法创建 PDF") }
        let legacy = page.id.hasPrefix("page-notein-") || page.id.hasPrefix("page-onenote-")
        let outputPage = Page(page.json)
        if legacy && page.paper != "PDF" { var v = outputPage.view; v["paperSize"] = .string("BOUNDLESS"); outputPage.view = v }
        for sheet in ExportPlan.sheets(page: outputPage, document: d, a4Gap: 32) {
            let media = CGRect(x: 0, y: 0, width: sheet.widthPoints, height: sheet.heightPoints)
            var box = media
            let boxData = Data(bytes: &box, count: MemoryLayout<CGRect>.size)
            c.beginPDFPage([kCGPDFContextMediaBox as String: boxData] as CFDictionary)
            c.setFillColor(rendererPaper(page, printing: printing).cgColor); c.fill(media)
            c.saveGState(); c.translateBy(x: 0, y: media.height); c.scaleBy(x: sheet.scale, y: -sheet.scale); c.translateBy(x: -sheet.source.minX, y: -sheet.source.minY); c.clip(to: sheet.source)
            let prior = NSGraphicsContext.current; NSGraphicsContext.current = NSGraphicsContext(cgContext: c, flipped: true)
            canvas.renderer.drawPaper(page, in: c, visible: sheet.source, printing: printing)
            canvas.renderer.draw(d, page: page, in: c, visible: sheet.source, printing: printing)
            NSGraphicsContext.current = prior; c.restoreGState(); c.endPDFPage()
        }
        c.closePDF(); return result as Data
    }
    private func rendererPaper(_ page: Page, printing: Bool) -> NSColor { canvas.renderer.paperColor(page, printing: printing) }
    @objc func printPage(_ sender: Any?) {
        do {
            guard let pdf = PDFDocument(data: try pdfData(printing: true)), let operation = pdf.printOperation(for: NSPrintInfo.shared, scalingMode: .pageScaleToFit, autoRotate: true) else { throw MojianError.invalid("无法准备打印") }
            operation.run()
        } catch { showError(error) }
    }
    @objc func saveNow(_ sender: Any?) { do { try session.flush(); updateStatus() } catch { showError(error) } }
    @objc func copy(_ sender: Any?) {
        guard var d = session.document, !canvas.selected.isEmpty else { return }
        for key in ["strokes", "objects", "textItems", "tags"] { d.json[key] = .array(d.json[key].array.filter { canvas.selected.contains($0["id"].string) }) }
        do {
            var assets = [String: Data]()
            for item in d.objects { for key in ["assetName", "sourceAssetName"] where !item[key].string.isEmpty {
                let name = item[key].string; assets[name] = try Data(contentsOf: session.store.assetURL(name, document: d))
            } }
            NSPasteboard.general.clearContents(); NSPasteboard.general.setString(UUID().uuidString, forType: .init("com.meta.mojian.selection"))
            clipboard = (d, assets, NSPasteboard.general.changeCount)
        } catch { showError(error) }
    }
    @objc func cut(_ sender: Any?) {
        guard session.canEdit else { return }
        copy(sender)
        if clipboard?.2 == NSPasteboard.general.changeCount { canvas.deleteSelection() }
    }
    @objc override func selectAll(_ sender: Any?) {
        guard let d = session.document else { return }
        canvas.selected = Set(["strokes", "objects", "textItems", "tags"].flatMap { d.json[$0].array.filter { !$0["isPageBackground"].bool() }.map { $0["id"].string } }); canvas.needsDisplay = true
    }
    @objc func paste(_ sender: Any?) {
        guard session.canEdit, let target = session.document else { return }
        do {
            if let (source, assets, count) = clipboard, count == NSPasteboard.general.changeCount {
                var copy = source, ids = Set<String>(), mapping = [String: String]()
                for (old, data) in assets { let name = JSON.id("pasted") + "." + (old as NSString).pathExtension; mapping[old] = name; try session.store.writeAsset(data, notebook: target.notebookID, page: target.pageID, name: name) }
                for key in ["strokes", "objects", "textItems", "tags"] { copy.json[key] = .array(copy.json[key].array.map { old in
                    var j = old; j["id"] = .string(JSON.id("pasted")); ids.insert(j["id"].string); j["layerId"] = .string(""); j["zOrder"] = .number(0)
                    for field in ["assetName", "sourceAssetName"] { if let name = mapping[j[field].string] { j[field] = .string(name) } }; return j
                }) }
                let b = copy.contentBounds; copy.transform(ids: ids, from: b, to: b.offsetBy(dx: 24, dy: 24))
                session.mutate { d in for key in ["strokes", "objects", "textItems", "tags"] { d.json[key] = .array(d.json[key].array + copy.json[key].array) } }; canvas.selected = ids
            } else if let image = NSImage(pasteboard: .general), let data = image.tiffRepresentation { try insertImageData(data, title: "粘贴的图片") }
            else if let text = NSPasteboard.general.string(forType: .string), !text.isEmpty {
                let p = canvas.viewport.document(CGPoint(x: 60, y: 100)); session.mutate { $0.texts.append(.object(["id": .string(JSON.id("text")), "x": .number(p.x), "y": .number(p.y), "width": .number(400), "height": .number(180), "fontSize": .number(16), "text": .string(text)])) }
            }
            canvas.rebind()
        } catch { showError(error) }
    }
    func validateMenuItem(_ menuItem: NSMenuItem) -> Bool {
        let action = menuItem.action
        if action == #selector(performUndo(_:)) { return session.canEdit && session.canUndo }
        if action == #selector(performRedo(_:)) { return session.canEdit && session.canRedo }
        if action == #selector(toggleMouseWriting(_:)) { menuItem.state = canvas.mouseWriting ? .on : .off }
        if action == #selector(toggleTheme(_:)) { menuItem.state = session.dark ? .on : .off }
        if action == #selector(chooseTool(_:)) { menuItem.state = PenTool.allCases[menuItem.tag] == session.settings.tool ? .on : .off }
        if action == #selector(chooseShape(_:)) { menuItem.state = ShapeKind.allCases[menuItem.tag] == session.settings.shape ? .on : .off }
        if action == #selector(chooseDefaultBackground(_:)) { menuItem.state = menuItem.representedObject as? String == session.defaultBackground ? .on : .off }
        if action == #selector(chooseColor(_:)) { menuItem.state = menuItem.tag == session.settings.color ? .on : .off }
        if action == #selector(newPage(_:)) { return !session.store.readOnly && session.section != nil }
        let edits: [Selector] = [ #selector(insertTextBlock(_:)), #selector(insertImage(_:)), #selector(renamePage(_:)), #selector(chooseBackground(_:)), #selector(paste(_:))]
        if edits.contains(where: { $0 == action }) { return session.canEdit }
        if action == #selector(newNotebook(_:)) || action == #selector(newSection(_:)) || action == #selector(importNotebook(_:)) { return !session.store.readOnly }
        if action == #selector(exportPDF(_:)) || action == #selector(printPage(_:)) { return session.page != nil }
        return true
    }
    func windowShouldClose(_ sender: NSWindow) -> Bool {
        canvas.cancelOperation(nil)
        do { try session.flush(); return true } catch { showError(error); return false }
    }
    func windowWillClose(_ notification: Notification) { closeHandler?() }
    func showError(_ error: Error) { let alert = NSAlert(error: error); alert.runModal() }
}

@MainActor private final class ToolbarProvider: NSObject, NSToolbarDelegate {
    weak var owner: MacWindow?
    init(owner: MacWindow) { self.owner = owner }
    func toolbarAllowedItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] { toolbarDefaultItemIdentifiers(toolbar) }
    func toolbarDefaultItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] { ["new", "undo", "redo", "tools", "text", "image", "fit"].map { NSToolbarItem.Identifier($0) } }
    func toolbar(_ toolbar: NSToolbar, itemForItemIdentifier itemIdentifier: NSToolbarItem.Identifier, willBeInsertedIntoToolbar flag: Bool) -> NSToolbarItem? { owner?.toolbarItem(itemIdentifier.rawValue) }
}