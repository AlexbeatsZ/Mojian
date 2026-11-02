import AppKit
import MojianCore

/// Black pane rows: notebooks and recursively nested sections, never pages (`workspace-ui.md`).
enum NavRow { case notebook(Notebook, index: Int), section(Notebook, Section, depth: Int) }
/// Gray pane rows: pages, with child-section headers only in recursive mode.
enum PageRow { case header(Section, depth: Int), page(Notebook, Section, Page, depth: Int) }
/// Hierarchy node carried by context-menu items.
enum NavNode { case notebook(Notebook), section(Notebook, Section), page(Notebook, Section, Page) }

/// Draw-based navigation row; avoids per-row Auto Layout for libraries with hundreds of pages.
@MainActor final class PaneRowView: NSView {
    enum Kind { case notebook, section, header, page }
    struct Model {
        var kind = Kind.page, title = "", subtitle = "", depth = 0, accent = Palette.accent
        var expanded = false, hasChildren = false, selected = false, active = false, pdf = false
    }
    var model = Model() { didSet { needsDisplay = true; setAccessibilityLabel(model.title) } }
    var onClick: ((_ chevron: Bool, _ double: Bool) -> Void)?
    var onMenu: (() -> NSMenu?)?
    override var isFlipped: Bool { true }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
    private var chevron: CGRect {
        switch model.kind {
        case .notebook: return CGRect(x: 5, y: 0, width: 24, height: bounds.height)
        case .section: return CGRect(x: 14 + CGFloat(model.depth) * 15, y: 0, width: 24, height: bounds.height)
        default: return .zero
        }
    }
    override func mouseDown(with event: NSEvent) {
        let p = convert(event.locationInWindow, from: nil)
        onClick?(model.hasChildren && chevron.contains(p), event.clickCount >= 2)
    }
    override func menu(for event: NSEvent) -> NSMenu? { onMenu?() }
    override func accessibilityPerformPress() -> Bool { onClick?(false, false); return true }
    override func draw(_ dirtyRect: NSRect) {
        let m = model
        switch m.kind {
        case .notebook, .section:
            if m.selected { Palette.selected.setFill(); NSBezierPath(roundedRect: bounds.insetBy(dx: 5, dy: 2), xRadius: 2, yRadius: 2).fill() }
            if m.hasChildren { drawChevron(in: chevron, expanded: m.expanded, color: m.kind == .notebook ? Palette.text : Palette.secondary) }
            var x = chevron.maxX
            if m.kind == .notebook {
                FluentIcon.image("notebook", size: 22, tint: m.accent).draw(in: CGRect(x: x, y: (bounds.height - 22) / 2, width: 22, height: 22), from: .zero, operation: .sourceOver, fraction: 1, respectFlipped: true, hints: nil)
                x += 30
            } else {
                m.accent.setFill(); NSBezierPath(roundedRect: CGRect(x: x, y: (bounds.height - 22) / 2, width: 4, height: 22), xRadius: 2, yRadius: 2).fill()
                x += 12
            }
            let strong = m.kind == .notebook ? m.active : m.selected
            let color = m.kind == .notebook ? (m.active ? Palette.text : Palette.secondary) : (m.selected ? Palette.accent : Palette.text)
            line(m.title, in: CGRect(x: x, y: 0, width: bounds.width - x - 10, height: bounds.height), size: 13, weight: strong ? .semibold : .regular, color: color)
        case .header:
            let box = CGRect(x: 6, y: 5, width: bounds.width - 12, height: bounds.height - 5)
            Palette.toolbar.setFill(); NSBezierPath(roundedRect: box, xRadius: 2, yRadius: 2).fill()
            let x = box.minX + 9 + CGFloat(m.depth) * 13
            m.accent.setFill(); NSBezierPath(roundedRect: CGRect(x: x, y: box.midY - 9, width: 3, height: 18), xRadius: 1, yRadius: 1).fill()
            line(m.title, in: CGRect(x: x + 11, y: box.minY, width: box.maxX - x - 19, height: box.height), size: 11, weight: .semibold, color: Palette.secondary)
        case .page:
            let box = CGRect(x: 6, y: 1, width: bounds.width - 12, height: bounds.height - 2)
            if m.selected { Palette.selected.setFill(); NSBezierPath(roundedRect: box, xRadius: 2, yRadius: 2).fill() }
            let x = box.minX + 13 + CGFloat(m.depth) * 13, right = box.maxX - (m.pdf ? 34 : 10)
            let titleHeight: CGFloat = m.subtitle.isEmpty ? box.height : 22
            line(m.title, in: CGRect(x: x, y: m.subtitle.isEmpty ? box.minY : box.minY + 8, width: right - x, height: titleHeight), size: 13, weight: m.selected ? .semibold : .regular, color: Palette.text)
            if !m.subtitle.isEmpty { line(m.subtitle, in: CGRect(x: x, y: box.minY + 30, width: right - x, height: 16), size: 10, weight: .regular, color: Palette.secondary) }
            if m.pdf { line("PDF", in: CGRect(x: box.maxX - 34, y: box.minY, width: 30, height: box.height), size: 9, weight: .semibold, color: Palette.muted, center: true) }
        }
    }
    private func line(_ text: String, in rect: CGRect, size: CGFloat, weight: NSFont.Weight, color: NSColor, center: Bool = false) {
        guard rect.width > 4 else { return }
        let style = NSMutableParagraphStyle(); style.lineBreakMode = .byTruncatingTail; style.alignment = center ? .center : .left
        let font = NSFont.systemFont(ofSize: size, weight: weight)
        let height = ceil(font.ascender - font.descender + font.leading)
        (text as NSString).draw(with: CGRect(x: rect.minX, y: rect.midY - height / 2, width: rect.width, height: height), options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine],
                                attributes: [.font: font, .foregroundColor: color, .paragraphStyle: style])
    }
    /// One Fluent chevron rotated for expansion; font glyphs would change baseline and center.
    private func drawChevron(in rect: CGRect, expanded: Bool, color: NSColor) {
        guard let c = NSGraphicsContext.current?.cgContext else { return }
        let image = FluentIcon.image("chevron_right", size: 16, tint: color)
        c.saveGState(); c.translateBy(x: rect.midX, y: rect.midY); if expanded { c.rotate(by: .pi / 2) }
        image.draw(in: CGRect(x: -8, y: -8, width: 16, height: 16), from: .zero, operation: .sourceOver, fraction: 1, respectFlipped: true, hints: nil)
        c.restoreGState()
    }
}

/// Virtualized row list used by both panes and page search.
@MainActor final class PaneList: NSObject, NSTableViewDataSource, NSTableViewDelegate {
    let table = NSTableView()
    let scroll = NSScrollView()
    var count: () -> Int = { 0 }
    var height: (Int) -> CGFloat = { _ in 42 }
    var configure: (PaneRowView, Int) -> Void = { _, _ in }
    init(background: NSColor, label: String) {
        super.init()
        let column = NSTableColumn(identifier: .init("row")); column.resizingMask = .autoresizingMask
        table.addTableColumn(column); table.headerView = nil; table.style = .plain
        table.backgroundColor = background; table.selectionHighlightStyle = .none; table.focusRingType = .none
        table.intercellSpacing = .zero; table.rowSizeStyle = .custom; table.columnAutoresizingStyle = .uniformColumnAutoresizingStyle
        table.dataSource = self; table.delegate = self; table.setAccessibilityLabel(label)
        scroll.documentView = table; scroll.hasVerticalScroller = true; scroll.autohidesScrollers = true; scroll.scrollerStyle = .overlay
        scroll.drawsBackground = true; scroll.backgroundColor = background; scroll.borderType = .noBorder
        scroll.translatesAutoresizingMaskIntoConstraints = false
    }
    func numberOfRows(in tableView: NSTableView) -> Int { count() }
    func tableView(_ tableView: NSTableView, heightOfRow row: Int) -> CGFloat { height(row) }
    func tableView(_ tableView: NSTableView, shouldSelectRow row: Int) -> Bool { false }
    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        let view = tableView.makeView(withIdentifier: .init("pane-row"), owner: nil) as? PaneRowView ?? {
            let created = PaneRowView(); created.identifier = .init("pane-row"); return created
        }()
        configure(view, row); return view
    }
}

/// Top-right search surface. Matches page titles across every notebook without loading page files.
@MainActor final class SearchController: NSViewController, NSSearchFieldDelegate {
    private let field = NSSearchField()
    private let list = PaneList(background: .clear, label: "搜索结果")
    private let notebooks: [Notebook]
    private let open: (Notebook, Section, Page) -> Void
    private var results = [(Notebook, Section, Page)]()
    init(notebooks: [Notebook], open: @escaping (Notebook, Section, Page) -> Void) {
        self.notebooks = notebooks; self.open = open; super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }
    override func loadView() {
        let root = NSView(frame: CGRect(x: 0, y: 0, width: 360, height: 380))
        field.placeholderString = "搜索页面标题"; field.delegate = self; field.translatesAutoresizingMaskIntoConstraints = false
        field.setAccessibilityLabel("搜索页面标题")
        list.scroll.drawsBackground = false; list.table.backgroundColor = .clear
        list.count = { [weak self] in self?.results.count ?? 0 }
        list.height = { _ in 52 }
        list.configure = { [weak self] view, row in
            guard let self else { return }
            let (n, s, p) = results[row]
            view.model = .init(kind: .page, title: p.title.isEmpty ? "无标题页" : p.title, subtitle: "\(n.title) › \(s.title)")
            view.onClick = { [weak self] _, _ in self?.open(n, s, p) }
            view.onMenu = nil
        }
        root.addSubview(field); root.addSubview(list.scroll)
        NSLayoutConstraint.activate([
            field.topAnchor.constraint(equalTo: root.topAnchor, constant: 12), field.leadingAnchor.constraint(equalTo: root.leadingAnchor, constant: 12),
            field.trailingAnchor.constraint(equalTo: root.trailingAnchor, constant: -12),
            list.scroll.topAnchor.constraint(equalTo: field.bottomAnchor, constant: 8), list.scroll.leadingAnchor.constraint(equalTo: root.leadingAnchor, constant: 4),
            list.scroll.trailingAnchor.constraint(equalTo: root.trailingAnchor, constant: -4), list.scroll.bottomAnchor.constraint(equalTo: root.bottomAnchor, constant: -6)
        ])
        view = root
    }
    override func viewDidAppear() { super.viewDidAppear(); view.window?.makeFirstResponder(field) }
    func controlTextDidChange(_ obj: Notification) {
        let query = field.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        results = query.isEmpty ? [] : Array(notebooks.lazy.flatMap { n in n.allSections.lazy.flatMap { s in s.pages.lazy.filter { $0.title.localizedCaseInsensitiveContains(query) }.map { (n, s, $0) } } }.prefix(300))
        list.table.reloadData()
    }
    func control(_ control: NSControl, textView: NSTextView, doCommandBy selector: Selector) -> Bool {
        guard selector == #selector(NSResponder.insertNewline(_:)), let first = results.first else { return false }
        open(first.0, first.1, first.2); return true
    }
}

extension Page {
    /// Gray-pane subtitle: time for today's edits, otherwise the date (Windows `NotePage.Subtitle`).
    var paneSubtitle: String {
        let raw = json["updatedAt"].string.isEmpty ? json["createdAt"].string : json["updatedAt"].string
        guard raw.count >= 19 else { return "" }
        let parser = DateFormatter(); parser.locale = Locale(identifier: "en_US_POSIX"); parser.timeZone = TimeZone(identifier: "UTC")
        parser.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        guard let date = parser.date(from: String(raw.prefix(19))) else { return "" }
        let output = DateFormatter(); output.dateFormat = Calendar.current.isDateInToday(date) ? "HH:mm" : "yyyy-MM-dd"
        return output.string(from: date)
    }
    var isPDF: Bool { AppearanceBridge.display(self).paper == "PDF" }
}
