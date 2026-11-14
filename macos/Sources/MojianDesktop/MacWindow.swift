import AppKit
import PDFKit
import UniformTypeIdentifiers
import MojianCore

enum RibbonTab: Int, CaseIterable {
    case home, insert, draw, view
    var title: String { ["开始", "插入", "绘图", "视图"][rawValue] }
}

/// Main note window. Layout follows the Windows/Android product (`windows-desktop.md`):
/// ribbon tabs, icon ribbon, 32 pt rail, 196 pt section and page panes, then the paper workspace.
/// macOS adaptations follow OneNote for Mac: tabs live in the unified title bar beside the traffic
/// lights, File/Options move to the menu bar and Settings window, flyouts are popovers and dialogs are sheets.
@MainActor final class MacWindow: NSWindowController, NSWindowDelegate, NSMenuItemValidation, NSToolbarDelegate, NSPopoverDelegate {
    let session: DesktopSession
    let canvas: NoteCanvas
    let sectionList = PaneList(background: Palette.sectionPane, label: "笔记本与分区")
    let pageList = PaneList(background: Palette.pagePane, label: "页面")
    let ribbon = FillView(Palette.toolbar)
    let ribbonStack = NSStackView()
    private let rail = FillView(Palette.rail)
    private let sectionPane = FillView(Palette.sectionPane)
    private let pagePane = FillView(Palette.pagePane)
    private let canvasHost = FillView(Palette.workspace)
    private let statusBadge = FillView(Palette.toolbar)
    private let statusLabel = NSTextField(labelWithString: "")
    var tabButtons = [TabButton]()
    var tab = RibbonTab.draw
    private var navRows = [NavRow](), pageRows = [PageRow]()
    /// Black-pane focus and gray-pane root; a deep recursive page never moves it.
    private(set) var root: Section?
    private var notebookFocused = false
    private(set) var fullPage = false
    private var transientSections = false, transientPages = false
    private var railButtons = [RibbonButton](), floatingButtons = [RibbonButton]()
    private var floatingStack = NSStackView()
    private var enterFullButton: RibbonButton!, exitFullButton: RibbonButton!
    var keyed = [String: RibbonButton]()
    var presetButtons = [String: RibbonButton]()
    var popover: NSPopover?
    var popoverPreset: String?
    var lastPopoverClose: (String, Date)?
    private var clipboard: (PageDocument, [String: Data], Int)?
    var closeHandler: (() -> Void)?

    init(session: DesktopSession) {
        self.session = session; canvas = NoteCanvas(session: session)
        let window = NSWindow(contentRect: CGRect(x: 0, y: 0, width: 1280, height: 820), styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        window.minSize = CGSize(width: 960, height: 600); window.title = "墨笺"
        window.titlebarAppearsTransparent = true; window.titleVisibility = .hidden; window.backgroundColor = Palette.chrome
        window.tabbingMode = .disallowed; window.appearance = NSAppearance(named: .darkAqua)
        super.init(window: window); window.delegate = self
        if !["--smoke-test", "--verify-reopen", "--ui-snapshots"].contains(where: CommandLine.arguments.contains) { window.setFrameAutosaveName("MojianMacMainWindow") }; window.center()
        root = session.section
        let toolbar = NSToolbar(identifier: "MojianRibbonTabs"); toolbar.delegate = self; toolbar.displayMode = .iconOnly; toolbar.allowsUserCustomization = false
        window.toolbar = toolbar; window.toolbarStyle = .unifiedCompact
        buildContent()
        session.changed = { [weak self] in self?.refresh() }
        session.onError = { [weak self] in self?.showError($0) }
        canvas.viewportChanged = { [weak self] in self?.updateZoomLabel() }
        canvas.selectionChanged = { [weak self] in self?.updateRibbonState() }
        canvas.editText = { [weak self] item, point in self?.editText(item, at: point) }
        selectTab(.draw); layoutChrome(); refresh(reloadTree: true)
        window.setContentSize(CGSize(width: 1280, height: 800))
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }

    // MARK: Title-bar ribbon tabs
    func toolbarDefaultItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] { [.init("tabs"), .flexibleSpace] }
    func toolbarAllowedItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] { toolbarDefaultItemIdentifiers(toolbar) }
    func toolbar(_ toolbar: NSToolbar, itemForItemIdentifier itemIdentifier: NSToolbarItem.Identifier, willBeInsertedIntoToolbar flag: Bool) -> NSToolbarItem? {
        guard itemIdentifier.rawValue == "tabs" else { return nil }
        if tabButtons.isEmpty { tabButtons = RibbonTab.allCases.map { t in TabButton(title: t.title) { [weak self] in self?.selectTab(t) } } }
        for (index, button) in tabButtons.enumerated() { button.isSelected = index == tab.rawValue }
        let item = NSToolbarItem(itemIdentifier: itemIdentifier)
        let stack = NSStackView(views: tabButtons); stack.spacing = 2; stack.setAccessibilityLabel("功能区选项卡")
        item.view = stack; item.label = "功能区"; return item
    }

    // MARK: Layout
    private func buildContent() {
        let content = FillView(Palette.workspace)
        ribbon.top = true; ribbon.bottom = true
        ribbonStack.orientation = .horizontal; ribbonStack.alignment = .centerY; ribbonStack.spacing = 0
        ribbonStack.translatesAutoresizingMaskIntoConstraints = false; ribbon.addSubview(ribbonStack)

        rail.right = true
        railButtons = [
            RibbonButton(icon: "navigation", tip: "折叠或展开分区栏", size: NSSize(width: 30, height: 32), iconSize: 20) { [weak self] _ in self?.toggleSidebar(nil) },
            RibbonButton(icon: "panel_left", tip: "折叠或展开页面栏", size: NSSize(width: 30, height: 32), iconSize: 20) { [weak self] _ in self?.togglePages(nil) }
        ]
        let railStack = NSStackView(views: railButtons); railStack.orientation = .vertical; railStack.spacing = 4
        railStack.translatesAutoresizingMaskIntoConstraints = false; rail.addSubview(railStack)

        sectionPane.right = true; pagePane.right = true
        let addSection = RibbonButton(icon: "add", tip: "新建笔记本或分区", size: NSSize(width: 38, height: 38), iconSize: 22) { [weak self] b in self?.showAddMenu(b) }
        let addPage = RibbonButton(icon: "add", tip: "新建页面", size: NSSize(width: 38, height: 38), iconSize: 22) { [weak self] _ in self?.newPage(nil) }
        keyed["addSection"] = addSection; keyed["addPage"] = addPage
        for (pane, add, header, list) in [(sectionPane, addSection, 44.0, sectionList), (pagePane, addPage, 48.0, pageList)] {
            pane.addSubview(add); pane.addSubview(list.scroll)
            NSLayoutConstraint.activate([
                add.trailingAnchor.constraint(equalTo: pane.trailingAnchor, constant: -7), add.centerYAnchor.constraint(equalTo: pane.topAnchor, constant: header / 2),
                list.scroll.topAnchor.constraint(equalTo: pane.topAnchor, constant: header), list.scroll.leadingAnchor.constraint(equalTo: pane.leadingAnchor),
                list.scroll.trailingAnchor.constraint(equalTo: pane.trailingAnchor, constant: -1), list.scroll.bottomAnchor.constraint(equalTo: pane.bottomAnchor)
            ])
        }
        configureLists()

        canvas.translatesAutoresizingMaskIntoConstraints = false; canvasHost.addSubview(canvas)
        let search = RibbonButton(icon: "search", tip: "搜索页面", size: NSSize(width: 34, height: 34), iconSize: 20) { [weak self] b in self?.showSearch(b) }
        enterFullButton = RibbonButton(icon: "full_screen_maximize", tip: "进入整页视图", size: NSSize(width: 34, height: 34), iconSize: 15) { [weak self] _ in self?.toggleFullPage(nil) }
        exitFullButton = RibbonButton(icon: "full_screen_minimize", tip: "退出整页视图", size: NSSize(width: 34, height: 34), iconSize: 15) { [weak self] _ in self?.toggleFullPage(nil) }
        for b in [search, enterFullButton!, exitFullButton!] { b.resting = Palette.canvasAction }
        let actions = NSStackView(views: [search, enterFullButton, exitFullButton]); actions.spacing = 4; actions.translatesAutoresizingMaskIntoConstraints = false
        floatingButtons = [
            RibbonButton(icon: "navigation", tip: "折叠或展开分区栏", size: NSSize(width: 34, height: 34), iconSize: 20) { [weak self] _ in self?.toggleSidebar(nil) },
            RibbonButton(icon: "panel_left", tip: "折叠或展开页面栏", size: NSSize(width: 34, height: 34), iconSize: 20) { [weak self] _ in self?.togglePages(nil) }
        ]
        for b in floatingButtons { b.resting = Palette.canvasAction }
        floatingStack = NSStackView(views: floatingButtons); floatingStack.orientation = .vertical; floatingStack.spacing = 4; floatingStack.translatesAutoresizingMaskIntoConstraints = false
        statusBadge.top = true; statusBadge.left = true
        statusLabel.font = .systemFont(ofSize: 11); statusLabel.textColor = Palette.secondary; statusLabel.translatesAutoresizingMaskIntoConstraints = false
        statusBadge.addSubview(statusLabel)
        for v in [actions, floatingStack, statusBadge] as [NSView] { canvasHost.addSubview(v) }
        NSLayoutConstraint.activate([
            canvas.leadingAnchor.constraint(equalTo: canvasHost.leadingAnchor), canvas.trailingAnchor.constraint(equalTo: canvasHost.trailingAnchor),
            canvas.topAnchor.constraint(equalTo: canvasHost.topAnchor), canvas.bottomAnchor.constraint(equalTo: canvasHost.bottomAnchor),
            actions.topAnchor.constraint(equalTo: canvasHost.topAnchor, constant: 7), actions.trailingAnchor.constraint(equalTo: canvasHost.trailingAnchor, constant: -7),
            floatingStack.topAnchor.constraint(equalTo: canvasHost.topAnchor, constant: 8), floatingStack.leadingAnchor.constraint(equalTo: canvasHost.leadingAnchor, constant: 8),
            // Sync/save status: compact bottom-right overlay with zero right/bottom margin.
            statusBadge.trailingAnchor.constraint(equalTo: canvasHost.trailingAnchor), statusBadge.bottomAnchor.constraint(equalTo: canvasHost.bottomAnchor),
            statusLabel.leadingAnchor.constraint(equalTo: statusBadge.leadingAnchor, constant: 8), statusLabel.trailingAnchor.constraint(equalTo: statusBadge.trailingAnchor, constant: -8),
            statusLabel.topAnchor.constraint(equalTo: statusBadge.topAnchor, constant: 4), statusLabel.bottomAnchor.constraint(equalTo: statusBadge.bottomAnchor, constant: -4)
        ])

        let body = NSStackView(views: [rail, sectionPane, pagePane, canvasHost]); body.orientation = .horizontal; body.spacing = 0; body.alignment = .top
        body.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(ribbon); content.addSubview(body)
        NSLayoutConstraint.activate([
            ribbon.topAnchor.constraint(equalTo: content.topAnchor), ribbon.leadingAnchor.constraint(equalTo: content.leadingAnchor), ribbon.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            ribbon.heightAnchor.constraint(equalToConstant: 50),
            ribbonStack.leadingAnchor.constraint(equalTo: ribbon.leadingAnchor, constant: 8), ribbonStack.centerYAnchor.constraint(equalTo: ribbon.centerYAnchor),
            body.leadingAnchor.constraint(equalTo: content.leadingAnchor), body.trailingAnchor.constraint(equalTo: content.trailingAnchor), body.bottomAnchor.constraint(equalTo: content.bottomAnchor),
            rail.widthAnchor.constraint(equalToConstant: 32), sectionPane.widthAnchor.constraint(equalToConstant: 196), pagePane.widthAnchor.constraint(equalToConstant: 196),
            railStack.topAnchor.constraint(equalTo: rail.topAnchor, constant: 6), railStack.centerXAnchor.constraint(equalTo: rail.centerXAnchor)
        ])
        // Full-page mode hides the ribbon; the body then starts at the top of the content view.
        bodyTop = body.topAnchor.constraint(equalTo: ribbon.bottomAnchor); bodyTopFull = body.topAnchor.constraint(equalTo: content.topAnchor)
        bodyTop.isActive = true
        for v in [rail, sectionPane, pagePane, canvasHost] { v.heightAnchor.constraint(equalTo: body.heightAnchor).isActive = true }
        canvasHost.setContentHuggingPriority(.defaultLow, for: .horizontal)
        window?.contentView = content
    }
    private var bodyTop: NSLayoutConstraint!, bodyTopFull: NSLayoutConstraint!

    private func layoutChrome() {
        window?.toolbar?.isVisible = !fullPage
        ribbon.isHidden = fullPage; rail.isHidden = fullPage
        bodyTop.isActive = !fullPage; bodyTopFull.isActive = fullPage
        sectionPane.isHidden = fullPage ? !transientSections : !session.showSectionPane
        pagePane.isHidden = fullPage ? !transientPages : !session.showPagePane
        floatingStack.isHidden = !fullPage; enterFullButton.isHidden = fullPage; exitFullButton.isHidden = !fullPage
        railButtons[0].isSelected = session.showSectionPane; railButtons[1].isSelected = session.showPagePane
        floatingButtons[0].isSelected = transientSections; floatingButtons[1].isSelected = transientPages
    }

    // MARK: Navigation panes
    private func configureLists() {
        sectionList.count = { [weak self] in self?.navRows.count ?? 0 }
        sectionList.height = { _ in 42 }
        sectionList.configure = { [weak self] view, row in
            guard let self, row < navRows.count else { return }
            switch navRows[row] {
            case .notebook(let n, let index):
                let active = n === session.notebook
                view.model = .init(kind: .notebook, title: n.title, accent: Palette.sections[index % 4], expanded: !session.isCollapsed(n.id),
                                   hasChildren: !n.sections.isEmpty, selected: active && notebookFocused, active: active)
                view.onClick = { [weak self] chevron, _ in chevron ? self?.toggle(n.id) : self?.selectNotebook(n) }
                view.onMenu = { [weak self] in self?.contextMenu(.notebook(n)) }
            case .section(let n, let s, let depth):
                view.model = .init(kind: .section, title: s.title, depth: depth, accent: Palette.sections[(depth + 1) % 4], expanded: !session.isCollapsed(s.id),
                                   hasChildren: !s.children.isEmpty, selected: !notebookFocused && s === root)
                view.onClick = { [weak self] chevron, _ in chevron ? self?.toggle(s.id) : self?.selectSection(n, s) }
                view.onMenu = { [weak self] in self?.contextMenu(.section(n, s)) }
            }
        }
        pageList.count = { [weak self] in self?.pageRows.count ?? 0 }
        pageList.height = { [weak self] row in
            guard let self, row < pageRows.count, case .header = pageRows[row] else { return 58 }
            return 37
        }
        pageList.configure = { [weak self] view, row in
            guard let self, row < pageRows.count else { return }
            switch pageRows[row] {
            case .header(let s, let depth):
                view.model = .init(kind: .header, title: s.title, depth: depth - 1, accent: Palette.sections[(depth + 1) % 4])
                view.onClick = nil; view.onMenu = nil
            case .page(let n, let s, let p, let depth):
                view.model = .init(kind: .page, title: p.title.isEmpty ? "无标题页" : p.title, subtitle: p.paneSubtitle, depth: depth, selected: p === session.page, pdf: p.isPDF)
                view.onClick = { [weak self] _, double in
                    guard let self else { return }
                    if double { renamePage(nil) } else if p !== session.page { navigate(n, s, p, root: root, focusNotebook: false) }
                }
                view.onMenu = { [weak self] in self?.contextMenu(.page(n, s, p)) }
            }
        }
    }
    private func rebuildRows() {
        navRows = []
        for (index, n) in session.notebooks.enumerated() {
            navRows.append(.notebook(n, index: index))
            guard !session.isCollapsed(n.id) else { continue }
            func add(_ s: Section, _ depth: Int) {
                navRows.append(.section(n, s, depth: depth))
                if !session.isCollapsed(s.id) { for child in s.children { add(child, depth + 1) } }
            }
            for s in n.sections { add(s, 0) }
        }
        pageRows = []
        guard let n = session.notebook, let r = root else { return }
        if session.settings.recursivePages {
            func add(_ s: Section, _ depth: Int) {
                // Header depth matches the black pane, so its color bar is the same section color.
                if depth > 0 { pageRows.append(.header(s, depth: depth)) }
                for p in s.pages { pageRows.append(.page(n, s, p, depth: depth)) }
                for child in s.children { add(child, depth + 1) }
            }
            add(r, 0)
        } else { pageRows = r.pages.map { .page(n, r, $0, depth: 0) } }
    }
    private func toggle(_ id: String) { session.toggleCollapsed(id); refresh() }
    private func selectNotebook(_ n: Notebook) {
        if n === session.notebook { notebookFocused = true; refresh(); return }
        let s = n.allSections.first
        navigate(n, s, s?.pages.first, root: s, focusNotebook: true)
    }
    private func selectSection(_ n: Notebook, _ s: Section) {
        if s === root && n === session.notebook && !notebookFocused { return }
        let candidates = session.settings.recursivePages ? s.all : [s]
        let owner = candidates.first { !$0.pages.isEmpty } ?? s
        navigate(n, owner, owner.pages.first, root: s, focusNotebook: false)
    }
    func navigate(_ n: Notebook, _ s: Section?, _ p: Page?, root target: Section?, focusNotebook: Bool) {
        do {
            try session.select(n, s, p)
            root = target ?? s; notebookFocused = focusNotebook
            canvas.rebind(resetViewport: true); refresh(reloadTree: true)
        } catch { showError(error); refresh() }
    }
    func refresh(reloadTree: Bool = false) {
        if let r = root, !(session.notebook?.allSections.contains(where: { $0 === r }) ?? false) { root = nil }
        if root == nil { root = session.section }
        rebuildRows()
        sectionList.table.reloadData(); pageList.table.reloadData()
        if reloadTree, let id = session.page?.id, let row = pageRows.firstIndex(where: { if case .page(_, _, let p, _) = $0 { return p.id == id }; return false }) {
            pageList.table.scrollRowToVisible(row)
        }
        let title = session.page.map { $0.title.isEmpty ? "无标题页" : $0.title } ?? "墨笺"
        window?.title = title + (session.store.readOnly ? " — 只读" : "")
        window?.representedURL = session.store.root; window?.isDocumentEdited = session.dirty
        statusLabel.stringValue = session.status
        statusLabel.textColor = session.saveError == nil ? Palette.secondary : .systemRed
        updateRibbonState(); canvas.needsDisplay = true
    }

    // MARK: Hierarchy commands
    private func showAddMenu(_ b: RibbonButton) {
        let menu = NSMenu(); menu.autoenablesItems = false
        menu.addItem(item("新建笔记本", #selector(newNotebook(_:)), nil, enabled: !session.store.readOnly))
        let target: NavNode? = notebookFocused || root == nil ? session.notebook.map { .notebook($0) } : session.notebook.flatMap { n in root.map { .section(n, $0) } }
        menu.addItem(item("新建分区", #selector(newSectionAt(_:)), target, enabled: !session.store.readOnly && target != nil))
        menu.popUp(positioning: nil, at: CGPoint(x: 0, y: b.bounds.height + 2), in: b)
    }
    func item(_ title: String, _ action: Selector, _ node: NavNode?, enabled: Bool = true, key: String = "") -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: key); item.target = self; item.representedObject = node; item.isEnabled = enabled; return item
    }
    /// Native context menus carry only operations that really exist on macOS.
    private func contextMenu(_ node: NavNode) -> NSMenu {
        let menu = NSMenu(); menu.autoenablesItems = false
        let writable = !session.store.readOnly
        menu.addItem(item("重命名…", #selector(renameNode(_:)), node, enabled: writable))
        switch node {
        case .notebook:
            menu.addItem(item("新建分区…", #selector(newSectionAt(_:)), node, enabled: writable))
            menu.addItem(.separator()); menu.addItem(item("导出笔记本 (.mjn)…", #selector(exportNotebook(_:)), node))
        case .section:
            menu.addItem(item("新建分区…", #selector(newSectionAt(_:)), node, enabled: writable))
        case .page:
            menu.addItem(.separator())
            menu.addItem(item("导出 PDF…", #selector(exportPDF(_:)), node)); menu.addItem(item("打印…", #selector(printPage(_:)), node))
        }
        menu.addItem(.separator()); menu.addItem(item("删除…", #selector(deleteNode(_:)), node, enabled: writable))
        return menu
    }
    /// Opens the page a menu item refers to before page-scoped output commands.
    private func focus(_ sender: Any?) -> Bool {
        guard let node = (sender as? NSMenuItem)?.representedObject as? NavNode else { return true }
        switch node {
        case .page(let n, let s, let p) where p !== session.page: navigate(n, s, p, root: root, focusNotebook: false); return session.page === p
        case .notebook(let n) where n !== session.notebook: selectNotebook(n); return session.notebook === n
        default: return true
        }
    }
    func ask(_ message: String, value: String = "", informative: String? = nil, completion: @escaping (String) -> Void) {
        let alert = NSAlert(); alert.messageText = message; if let informative { alert.informativeText = informative }
        alert.addButton(withTitle: "确定"); alert.addButton(withTitle: "取消")
        let field = NSTextField(string: value); field.frame = CGRect(x: 0, y: 0, width: 300, height: 24); alert.accessoryView = field
        alert.window.initialFirstResponder = field
        present(alert) { response in
            let text = field.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
            if response == .alertFirstButtonReturn, !text.isEmpty { completion(text) }
        }
    }
    /// Sheets on the note window (macOS convention); app-modal only when no window is visible.
    func present(_ alert: NSAlert, completion: @escaping (NSApplication.ModalResponse) -> Void) {
        if let window, window.isVisible { alert.beginSheetModal(for: window, completionHandler: completion) }
        else { completion(alert.runModal()) }
    }
    func run(_ action: () throws -> Void) {
        do { try action(); canvas.rebind(resetViewport: true); refresh(reloadTree: true) } catch { showError(error); refresh() }
    }
    @objc func renameNode(_ sender: NSMenuItem) {
        guard let node = sender.representedObject as? NavNode else { return }
        switch node {
        case .notebook(let n): ask("重命名笔记本", value: n.title) { [weak self] t in self?.run { try self?.session.rename(n, t) } }
        case .section(_, let s): ask("重命名分区", value: s.title) { [weak self] t in self?.run { try self?.session.rename(s, t) } }
        case .page(_, _, let p): ask("重命名页面", value: p.title) { [weak self] t in self?.run { try self?.session.rename(p, t) } }
        }
    }
    @objc func newSectionAt(_ sender: NSMenuItem) {
        guard let node = sender.representedObject as? NavNode else { return }
        let (n, parent): (Notebook, Section?) = { if case .section(let n, let s) = node { return (n, s) }; if case .notebook(let n) = node { return (n, nil) }; return (session.notebook!, nil) }()
        ask(parent == nil ? "新建分区" : "新建子分区", value: "新分区") { [weak self] t in
            self?.run { try self?.session.addSection(title: t, in: n, parent: parent); self?.root = self?.session.section; self?.notebookFocused = false }
        }
    }
    @objc func deleteNode(_ sender: NSMenuItem) {
        guard let node = sender.representedObject as? NavNode else { return }
        let alert = NSAlert(); alert.alertStyle = .critical; alert.addButton(withTitle: "删除"); alert.addButton(withTitle: "取消")
        alert.buttons.first?.hasDestructiveAction = true
        switch node {
        case .notebook(let n):
            alert.messageText = "删除笔记本“\(n.title)”？"
            alert.informativeText = "将同时删除其中 \(n.allSections.count) 个分区和 \(n.pages.count) 个页面。文件会移到废纸篓。"
        case .section(_, let s):
            alert.messageText = "删除分区“\(s.title)”？"
            alert.informativeText = "将同时删除 \(s.all.count - 1) 个子分区和 \(s.all.flatMap(\.pages).count) 个页面。文件会移到废纸篓。"
        case .page(_, _, let p):
            alert.messageText = "删除页面“\(p.title.isEmpty ? "无标题页" : p.title)”？"; alert.informativeText = "页面文件和附件会移到废纸篓。"
        }
        present(alert) { [weak self] response in
            guard let self, response == .alertFirstButtonReturn else { return }
            run {
                switch node {
                case .notebook(let n): try self.session.delete(n)
                case .section(let n, let s): try self.session.delete(s, in: n)
                case .page(let n, let s, let p): try self.session.delete(p, from: s, in: n)
                }
            }
        }
    }
    @objc func newNotebook(_ sender: Any?) {
        ask("新建笔记本", value: "新笔记本") { [weak self] t in
            self?.run { try self?.session.addNotebook(title: t); self?.root = self?.session.section; self?.notebookFocused = false }
        }
    }
    @objc func newSection(_ sender: NSMenuItem) {
        guard let n = session.notebook else { return }
        let parent = sender.tag == 1 ? root : nil
        ask(parent == nil ? "新建分区" : "新建子分区", value: "新分区") { [weak self] t in
            self?.run { try self?.session.addSection(title: t, in: n, parent: parent); self?.root = self?.session.section; self?.notebookFocused = false }
        }
    }
    /// Page/PDF chooser adapted as a sheet: paper template, baseline and title; remembers the paper.
    @objc func newPage(_ sender: Any?) {
        guard !session.store.readOnly, let target = root ?? session.section else { return }
        let alert = NSAlert(); alert.messageText = "新建页面"; alert.addButton(withTitle: "创建"); alert.addButton(withTitle: "取消")
        let title = NSTextField(string: "无标题页")
        let paper = NSSegmentedControl(labels: ["无界纸张", "A4 连续纸张"], trackingMode: .selectOne, target: nil, action: nil)
        paper.selectedSegment = session.lastPaper == "A4" ? 1 : 0
        let background = NSPopUpButton(); for b in Background.allCases { background.addItem(withTitle: b.title); background.lastItem?.representedObject = b.rawValue }
        background.selectItem(at: Background.allCases.firstIndex { $0.rawValue == session.defaultBackground } ?? 0)
        let grid = NSGridView(views: [[NSTextField(labelWithString: "标题"), title], [NSTextField(labelWithString: "纸张"), paper], [NSTextField(labelWithString: "背景"), background]])
        grid.rowSpacing = 10; grid.columnSpacing = 10; grid.frame = CGRect(x: 0, y: 0, width: 340, height: 96)
        title.widthAnchor.constraint(equalToConstant: 260).isActive = true
        alert.accessoryView = grid; alert.window.initialFirstResponder = title
        present(alert) { [weak self] response in
            guard let self, response == .alertFirstButtonReturn else { return }
            let name = title.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
            session.lastPaper = paper.selectedSegment == 1 ? "A4" : "BOUNDLESS"
            run { try self.session.addPage(title: name.isEmpty ? "无标题页" : name, paper: self.session.lastPaper, background: background.selectedItem?.representedObject as? String, in: target) }
        }
    }
    @objc func renamePage(_ sender: Any?) {
        guard let page = session.page, !session.store.readOnly else { return }
        ask("重命名页面", value: page.title) { [weak self] t in self?.run { try self?.session.rename(page, t) } }
    }
    @objc func deleteCurrentPage(_ sender: Any?) {
        guard let n = session.notebook, let s = session.section, let p = session.page else { return }
        let item = NSMenuItem(); item.representedObject = NavNode.page(n, s, p); deleteNode(item)
    }

    // MARK: View state
    @objc func toggleFullPage(_ sender: Any?) {
        fullPage.toggle(); transientSections = false; transientPages = false; popover?.close(); layoutChrome()
    }
    @objc func toggleSidebar(_ sender: Any?) {
        if fullPage { transientSections.toggle() } else { session.showSectionPane.toggle(); session.savePreferences() }
        layoutChrome()
    }
    @objc func togglePages(_ sender: Any?) {
        if fullPage { transientPages.toggle() } else { session.showPagePane.toggle(); session.savePreferences() }
        layoutChrome()
    }
    @objc func fullScreen(_ sender: Any?) { window?.toggleFullScreen(sender) }
    private func showSearch(_ b: RibbonButton) {
        popover?.close()
        let controller = SearchController(notebooks: session.notebooks) { [weak self] n, s, p in
            self?.popover?.close(); self?.navigate(n, s, p, root: s, focusNotebook: false)
        }
        let p = NSPopover(); p.behavior = .transient; p.contentViewController = controller; p.contentSize = CGSize(width: 360, height: 380)
        p.appearance = NSAppearance(named: .darkAqua); p.delegate = self; popover = p; popoverPreset = nil
        p.show(relativeTo: b.bounds, of: b, preferredEdge: .maxY)
    }
    func popoverDidClose(_ notification: Notification) {
        if let id = popoverPreset { lastPopoverClose = (id, Date()) }
        popoverPreset = nil
    }

    // MARK: Editing commands
    @objc func performUndo(_ sender: Any?) { session.undo(); canvas.rebind() }
    @objc func performRedo(_ sender: Any?) { session.redo(); canvas.rebind() }
    @objc func fitPage(_ sender: Any?) { canvas.fitPage() }
    @objc func zoomIn(_ sender: Any?) { canvas.zoom(canvas.viewport.scale * 1.25) }
    @objc func zoomOut(_ sender: Any?) { canvas.zoom(canvas.viewport.scale / 1.25) }
    @objc func actualSize(_ sender: Any?) { canvas.zoom(1) }
    @objc func toggleTheme(_ sender: Any?) { session.dark.toggle(); session.savePreferences(); canvas.needsDisplay = true; updateRibbonState() }
    @objc func chooseBackground(_ sender: NSMenuItem) {
        do { try session.setBackground(sender.representedObject as? String ?? "BLANK") } catch { showError(error) }
    }
    @objc func choosePaper(_ sender: NSMenuItem) {
        do { try session.setPaper(sender.representedObject as? String ?? "BOUNDLESS"); canvas.rebind() } catch { showError(error) }
    }
    @objc func toggleTitle(_ sender: Any?) {
        guard let page = session.displayPage else { return }
        do { try session.setTitleVisible(!page.view["pageTitleVisible"].bool(true)) } catch { showError(error) }
    }
    @objc func chooseDefaultBackground(_ sender: NSMenuItem) { session.defaultBackground = sender.representedObject as? String ?? "GRID_STANDARD"; session.savePreferences() }
    @objc func chooseTool(_ sender: NSMenuItem) { session.choose(PenTool.allCases[sender.tag]); session.mouseWriting = true; session.savePreferences(); updateRibbonState() }
    @objc func chooseShape(_ sender: NSMenuItem) {
        guard var preset = session.presets.first(where: { $0.tool == .shape }) else { return }
        preset.shape = ShapeKind.allCases[sender.tag]; session.update(preset); session.apply(preset)
    }
    @objc func chooseColor(_ sender: NSMenuItem) { var p = session.active; p.color = sender.tag; session.update(p); rebuildRibbon() }
    @objc func chooseWidth(_ sender: NSMenuItem) { var p = session.active; p.widthMM = (sender.representedObject as? Double) ?? 0.4; session.update(p) }
    @objc func toggleMouseWriting(_ sender: Any?) { session.mouseWriting.toggle(); session.savePreferences(); updateRibbonState(); canvas.needsDisplay = true }
    @objc func clearPage(_ sender: Any?) {
        guard session.canEdit else { return }
        let alert = NSAlert(); alert.messageText = "清除当前页的所有内容？"; alert.informativeText = "PDF 页面背景会保留。可以撤销。"
        alert.addButton(withTitle: "清纸"); alert.addButton(withTitle: "取消"); alert.buttons.first?.hasDestructiveAction = true
        present(alert) { [weak self] response in
            guard let self, response == .alertFirstButtonReturn else { return }
            canvas.selected.removeAll(); session.clearPage(); canvas.rebind()
        }
    }
    @objc func insertTextBlock(_ sender: Any?) { editText(nil, at: canvas.viewport.document(CGPoint(x: 60, y: 100))) }
    func editText(_ item: JSON?, at point: CGPoint) {
        guard session.canEdit else { return }
        let alert = NSAlert(); alert.messageText = item == nil ? "插入文字" : "编辑文字"; alert.addButton(withTitle: "保存"); alert.addButton(withTitle: "取消")
        let editor = NSTextView(frame: CGRect(x: 0, y: 0, width: 460, height: 180)); editor.isRichText = false; editor.font = .systemFont(ofSize: 16); editor.string = item?["text"].string ?? ""
        let scroller = NSScrollView(); scroller.documentView = editor; scroller.hasVerticalScroller = true; scroller.frame = editor.frame
        alert.accessoryView = scroller; alert.window.initialFirstResponder = editor
        present(alert) { [weak self] response in
            guard let self, response == .alertFirstButtonReturn, !editor.string.isEmpty else { return }
            var value = item ?? .object(["id": .string(JSON.id("text")), "x": .number(point.x), "y": .number(point.y), "width": .number(400), "height": .number(200), "fontSize": .number(16)])
            value["text"] = .string(editor.string)
            session.mutate { d in if let index = d.texts.firstIndex(where: { $0["id"] == value["id"] }) { d.texts[index] = value } else { d.texts.append(value) } }; canvas.rebind()
        }
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
        if panel.runModal() == .OK, let url = panel.url { run { try session.importNotebook(url); root = session.section; notebookFocused = false } }
    }
    @objc func exportNotebook(_ sender: Any?) {
        guard focus(sender) else { return }
        let panel = NSSavePanel(); panel.allowedContentTypes = [UTType(filenameExtension: "mjn") ?? .zip]; panel.nameFieldStringValue = (session.notebook?.title ?? "笔记本") + ".mjn"
        if panel.runModal() == .OK, let url = panel.url {
            do { let n = try session.exchangeNotebook(); let store = try WorkspaceStore(root: session.store.root); try NotebookArchive.export(n, store: store, to: url) } catch { showError(error) }
        }
    }
    @objc func exportPDF(_ sender: Any?) {
        guard focus(sender) else { return }
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
            c.setFillColor(canvas.renderer.paperColor(page, printing: printing).cgColor); c.fill(media)
            c.saveGState(); c.translateBy(x: 0, y: media.height); c.scaleBy(x: sheet.scale, y: -sheet.scale); c.translateBy(x: -sheet.source.minX, y: -sheet.source.minY); c.clip(to: sheet.source)
            let prior = NSGraphicsContext.current; NSGraphicsContext.current = NSGraphicsContext(cgContext: c, flipped: true)
            canvas.renderer.drawPaper(page, in: c, visible: sheet.source, printing: printing)
            canvas.renderer.draw(d, page: page, in: c, visible: sheet.source, printing: printing)
            NSGraphicsContext.current = prior; c.restoreGState(); c.endPDFPage()
        }
        c.closePDF(); return result as Data
    }
    @objc func printPage(_ sender: Any?) {
        guard focus(sender) else { return }
        do {
            guard let pdf = PDFDocument(data: try pdfData(printing: true)), let operation = pdf.printOperation(for: NSPrintInfo.shared, scalingMode: .pageScaleToFit, autoRotate: true) else { throw MojianError.invalid("无法准备打印") }
            if let window { operation.runModal(for: window, delegate: nil, didRun: nil, contextInfo: nil) } else { operation.run() }
        } catch { showError(error) }
    }
    @objc func saveNow(_ sender: Any?) { do { try session.flush(); refresh() } catch { showError(error) } }
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
    @objc func delete(_ sender: Any?) { if session.canEdit { canvas.deleteSelection() } }
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
        let action = menuItem.action, writable = !session.store.readOnly
        if action == #selector(performUndo(_:)) { return session.canEdit && session.canUndo }
        if action == #selector(performRedo(_:)) { return session.canEdit && session.canRedo }
        if action == #selector(toggleMouseWriting(_:)) { menuItem.state = session.mouseWriting ? .on : .off }
        if action == #selector(toggleTheme(_:)) { menuItem.state = session.dark ? .on : .off }
        if action == #selector(toggleFullPage(_:)) { menuItem.state = fullPage ? .on : .off }
        if action == #selector(toggleSidebar(_:)) { menuItem.state = session.showSectionPane ? .on : .off }
        if action == #selector(togglePages(_:)) { menuItem.state = session.showPagePane ? .on : .off }
        if action == #selector(chooseTool(_:)) { menuItem.state = PenTool.allCases[menuItem.tag] == session.settings.tool ? .on : .off }
        if action == #selector(chooseShape(_:)) { menuItem.state = ShapeKind.allCases[menuItem.tag] == session.settings.shape ? .on : .off }
        if action == #selector(chooseDefaultBackground(_:)) { menuItem.state = menuItem.representedObject as? String == session.defaultBackground ? .on : .off }
        if action == #selector(chooseColor(_:)) { menuItem.state = menuItem.tag == session.active.color ? .on : .off; return [.pen, .highlighter, .shape].contains(session.active.tool) }
        if action == #selector(chooseWidth(_:)) { return [.pen, .shape].contains(session.active.tool) }
        if action == #selector(chooseBackground(_:)) { menuItem.state = menuItem.representedObject as? String == session.displayPage?.view["backgroundFormat"].string ? .on : .off }
        if action == #selector(choosePaper(_:)) { menuItem.state = menuItem.representedObject as? String == session.displayPage?.paper ? .on : .off; return session.canEdit && session.displayPage?.paper != "PDF" }
        if action == #selector(newPage(_:)) { return writable && (root ?? session.section) != nil }
        let edits: [Selector] = [#selector(insertTextBlock(_:)), #selector(insertImage(_:)), #selector(renamePage(_:)), #selector(chooseBackground(_:)), #selector(paste(_:)),
                                 #selector(cut(_:)), #selector(delete(_:)), #selector(clearPage(_:)), #selector(toggleTitle(_:)), #selector(deleteCurrentPage(_:))]
        if edits.contains(where: { $0 == action }) { return session.canEdit }
        if action == #selector(newNotebook(_:)) || action == #selector(newSection(_:)) || action == #selector(importNotebook(_:)) { return writable }
        if action == #selector(exportPDF(_:)) || action == #selector(printPage(_:)) { return session.page != nil || menuItem.representedObject != nil }
        return true
    }
    func windowShouldClose(_ sender: NSWindow) -> Bool {
        canvas.cancelOperation(nil)
        do { try session.flush(); return true } catch { showError(error); return false }
    }
    func windowWillClose(_ notification: Notification) { closeHandler?() }
    func showError(_ error: Error) {
        let alert = NSAlert(error: error)
        if let window, window.isVisible, window.attachedSheet == nil { alert.beginSheetModal(for: window) } else { alert.runModal() }
    }
}
