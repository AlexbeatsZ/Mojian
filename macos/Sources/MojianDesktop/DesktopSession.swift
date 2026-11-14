import AppKit
import MojianCore

@MainActor final class DesktopSession {
    let store: CanonicalStore
    private let preferences: UserDefaults
    private let preferenceKey: String
    private let writer: DesktopWriter
    private var delayedSave: DispatchWorkItem?
    private var histories = [String: ([PageDocument], [PageDocument])]()
    private(set) var notebooks: [Notebook]
    private(set) var notebook: Notebook?
    private(set) var section: Section?
    private(set) var page: Page?
    var document: PageDocument?
    var settings = Settings()
    var dark = true
    var defaultBackground = "GRID_STANDARD"
    var presets = ToolPreset.defaults
    private(set) var activePreset = "pen-black"
    /// macOS adaptation: mouse/trackpad either selects content or uses the active tool (OneNote for Mac).
    var mouseWriting = false
    var showSectionPane = true
    var showPagePane = true
    var lastPaper = "BOUNDLESS"
    /// Expansion is local viewing state, so browsing never rewrites the shared index.
    private var collapsed = Set<String>()
    var changed: (() -> Void)?
    var onError: ((Error) -> Void)?
    private(set) var dirty = false
    private(set) var saveError: Error?
    private var generation = 0
    private var completionEpoch = 0
    private var libraryDirty = false
    var canEdit: Bool { !store.readOnly && page != nil }
    var canUndo: Bool { page.map { !(histories[$0.id]?.0.isEmpty ?? true) } ?? false }
    var canRedo: Bool { page.map { !(histories[$0.id]?.1.isEmpty ?? true) } ?? false }
    var status: String {
        if let saveError { return "保存失败：\(saveError.localizedDescription)" }
        if dirty { return "正在保存…" }
        if store.readOnly { return "外部笔记库 · 只读" }
        return store.recoveredFiles.isEmpty ? "已保存在本机" : "已从上一修订恢复 · 原文件保留"
    }
    init(store: CanonicalStore, preferences: UserDefaults = .standard) throws {
        self.store = store; self.preferences = preferences; writer = DesktopWriter(store: store)
        preferenceKey = "workspace:" + store.root.path
        notebooks = try store.loadLibrary()
        if let data = preferences.data(forKey: "drawing"), let value = try? JSONDecoder().decode(Settings.self, from: data) { settings = value }
        dark = preferences.object(forKey: "darkPages") as? Bool ?? true
        defaultBackground = preferences.string(forKey: "defaultBackground") ?? "GRID_STANDARD"
        if let data = preferences.data(forKey: "toolPresets"), let value = try? JSONDecoder().decode([ToolPreset].self, from: data), !value.isEmpty { presets = value }
        let presetList = presets, tool = settings.tool
        activePreset = preferences.string(forKey: "activePreset").flatMap { id in presetList.first { $0.id == id }?.id }
            ?? presetList.first { $0.tool == tool }?.id ?? presetList[0].id
        mouseWriting = preferences.bool(forKey: "mouseWriting")
        showSectionPane = preferences.object(forKey: "showSectionPane") as? Bool ?? true
        showPagePane = preferences.object(forKey: "showPagePane") as? Bool ?? true
        lastPaper = preferences.string(forKey: "lastPaper") ?? "BOUNDLESS"
        if let ids = preferences.stringArray(forKey: preferenceKey + ":collapsed") { collapsed = Set(ids) }
        else { collapsed = Set(notebooks.flatMap(\.allSections).filter { !$0.expanded }.map(\.id)) }
        let last = preferences.string(forKey: preferenceKey + ":page")
        notebook = notebooks.first(where: { $0.pages.contains { $0.id == last } }) ?? notebooks.first
        section = notebook?.allSections.first(where: { $0.pages.contains { $0.id == last } }) ?? notebook?.allSections.first
        page = section?.pages.first(where: { $0.id == last }) ?? section?.pages.first
        if let n = notebook, let p = page { document = try store.loadPage(notebook: n.id, page: p.id) }
    }
    var displayPage: Page? { page.map { AppearanceBridge.display($0, dark: dark) } }
    func savePreferences() {
        preferences.set(try? JSONEncoder().encode(settings), forKey: "drawing")
        preferences.set(dark, forKey: "darkPages"); preferences.set(defaultBackground, forKey: "defaultBackground")
        preferences.set(page?.id, forKey: preferenceKey + ":page")
        preferences.set(try? JSONEncoder().encode(presets), forKey: "toolPresets"); preferences.set(activePreset, forKey: "activePreset")
        preferences.set(mouseWriting, forKey: "mouseWriting"); preferences.set(lastPaper, forKey: "lastPaper")
        preferences.set(showSectionPane, forKey: "showSectionPane"); preferences.set(showPagePane, forKey: "showPagePane")
        preferences.set(Array(collapsed).sorted(), forKey: preferenceKey + ":collapsed")
    }
    func isCollapsed(_ id: String) -> Bool { collapsed.contains(id) }
    func toggleCollapsed(_ id: String) {
        if collapsed.remove(id) == nil { collapsed.insert(id) }; savePreferences()
    }
    var active: ToolPreset { presets.first { $0.id == activePreset } ?? presets[0] }
    func apply(_ preset: ToolPreset) {
        activePreset = preset.id; preset.apply(to: &settings); savePreferences(); changed?()
    }
    func update(_ preset: ToolPreset) {
        guard let index = presets.firstIndex(where: { $0.id == preset.id }) else { return }
        presets[index] = preset
        if preset.id == activePreset { preset.apply(to: &settings) }
        savePreferences(); changed?()
    }
    func addPreset(_ tool: PenTool) {
        let preset = ToolPreset.added(tool)
        // Add-only: a new pen/highlighter joins the end of its own ribbon group.
        let index = presets.lastIndex { $0.tool == tool }.map { $0 + 1 } ?? presets.count
        presets.insert(preset, at: index); mouseWriting = true; apply(preset)
    }
    func viewport() -> Viewport {
        guard let id = page?.id, let values = preferences.array(forKey: preferenceKey + ":view:" + id) as? [Double], values.count == 3 else { return Viewport(scale: 1, x: 32, y: 24) }
        return Viewport(scale: values[0], x: values[1], y: values[2])
    }
    func remember(_ viewport: Viewport) {
        guard let id = page?.id else { return }
        preferences.set([viewport.scale, viewport.x, viewport.y], forKey: preferenceKey + ":view:" + id)
    }
    func select(_ n: Notebook, _ s: Section?, _ p: Page?) throws {
        try flush()
        let next = try p.map { try store.loadPage(notebook: n.id, page: $0.id) }
        notebook = n; section = s; page = p; document = next
        savePreferences(); changed?()
    }
    func mutate(_ action: (inout PageDocument) -> Void) {
        guard canEdit, var d = document else { return }
        let before = d; action(&d)
        guard d != before else { return }
        pushUndo(before); document = d; contentChanged()
    }
    func commitPreview(_ before: PageDocument) {
        guard canEdit, document != before else { return }
        pushUndo(before); contentChanged()
    }
    private func pushUndo(_ before: PageDocument) {
        var h = histories[before.pageID] ?? ([], [])
        h.0.append(before); if h.0.count > 100 { h.0.removeFirst() }; h.1 = []; histories[before.pageID] = h
    }
    private func contentChanged() {
        generation += 1; dirty = true
        if let p = displayPage, let d = document {
            let b = d.contentBounds, gap = 32.0
            var source = page!.json["appearance"]
            if p.paper == "A4", !b.isNull {
                let count = max(p.sheetCount, Int(max(0, b.maxY + 24 * unitsPerMM + gap) / (p.height + gap)) + 1)
                if count != p.sheetCount { source["sheetCount"] = .number(Double(min(10000, count))); page!.json["appearance"] = source; libraryDirty = true }
            } else if p.paper == "BOUNDLESS", !b.isNull {
                let w = max(p.width, b.maxX + 24 * unitsPerMM), h = max(p.height, b.maxY + 24 * unitsPerMM)
                if w != p.width || h != p.height { source["infiniteWidth"] = .number(w); source["infiniteHeight"] = .number(h); page!.json["appearance"] = source; libraryDirty = true }
            }
        }
        scheduleSave(); changed?()
    }
    private func scheduleSave() {
        delayedSave?.cancel()
        let task = DispatchWorkItem { [weak self] in self?.saveInBackground() }
        delayedSave = task; DispatchQueue.main.asyncAfter(deadline: .now() + 0.45, execute: task)
    }
    private func saveInBackground() {
        guard dirty, let d = document else { return }
        let epoch = completionEpoch
        let current = generation, library: JSON? = libraryDirty ? .array(notebooks.map(\.json)) : nil
        writer.enqueue(d, library: library, generation: current) { [weak self] result in
            guard let self, self.completionEpoch == epoch, self.page?.id == d.pageID else { return }
            switch result {
            case .success:
                if self.generation == current { self.dirty = false; self.libraryDirty = false }; self.saveError = nil
            case .failure(let error): self.saveError = error; self.dirty = true
            }
            self.changed?()
        }
    }
    /// Navigation and close are refused when the pending canonical snapshot cannot be saved.
    func flush() throws {
        delayedSave?.cancel(); delayedSave = nil; completionEpoch += 1
        do {
            let library: JSON? = libraryDirty ? .array(notebooks.map(\.json)) : nil
            try writer.flush(dirty ? document : nil, library: library, generation: generation)
            dirty = false; libraryDirty = false; saveError = nil; savePreferences()
        } catch { saveError = error; dirty = true; changed?(); throw error }
    }
    func undo() {
        guard canEdit, let d = document, var h = histories[d.pageID], let prior = h.0.popLast() else { return }
        h.1.append(d); histories[d.pageID] = h; document = prior; contentChanged()
    }
    func redo() {
        guard canEdit, let d = document, var h = histories[d.pageID], let next = h.1.popLast() else { return }
        h.0.append(d); histories[d.pageID] = h; document = next; contentChanged()
    }
    func choose(_ tool: PenTool) {
        if let preset = presets.first(where: { $0.tool == tool && $0.id == activePreset }) ?? presets.first(where: { $0.tool == tool }) { apply(preset) }
        else { settings.tool = tool; savePreferences(); changed?() }
    }
    func addPage(title: String, paper: String, background: String? = nil, in target: Section? = nil) throws {
        guard !store.readOnly, let n = notebook, let s = target ?? section else { throw MojianError.invalid("请先选择可编辑分区") }
        try flush()
        let p = CanonicalStore.newPage(title: title, paper: paper, background: background ?? defaultBackground)
        let now = ISO8601DateFormatter().string(from: Date())
        p.json["createdAt"] = .string(now); p.json["updatedAt"] = .string(now)
        try store.writePage(PageDocument(notebook: n.id, page: p.id), incrementRevision: false)
        s.pages.append(p)
        do { try store.saveLibrary(notebooks) } catch { s.pages.removeAll { $0.id == p.id }; throw error }
        try select(n, s, p)
    }
    func addNotebook(title: String) throws {
        guard !store.readOnly else { throw MojianError.invalid("当前笔记库为只读") }; try flush()
        let n = Notebook(title: title, sections: [Section(title: "笔记")]); notebooks.append(n)
        do { try store.saveLibrary(notebooks) } catch { notebooks.removeLast(); throw error }
        try select(n, n.sections.first, nil)
    }
    /// A notebook is always created at library root; a section under `parent`, or at the notebook root.
    func addSection(title: String, in target: Notebook? = nil, parent: Section?) throws {
        guard !store.readOnly, let n = target ?? notebook else { throw MojianError.invalid("请先打开可编辑笔记本") }; try flush()
        let s = Section(title: title)
        if let parent { parent.children.append(s) } else { n.sections.append(s) }
        do { try store.saveLibrary(notebooks) } catch {
            if let parent { parent.children.removeAll { $0.id == s.id } } else { n.sections.removeAll { $0.id == s.id } }; throw error
        }
        if let parent { collapsed.remove(parent.id) }
        try select(n, s, nil)
    }
    private func commitLibrary(_ change: () -> Void, revert: () -> Void) throws {
        guard !store.readOnly else { throw MojianError.invalid("当前笔记库为只读") }; try flush()
        change()
        do { try store.saveLibrary(notebooks); changed?() } catch { revert(); throw error }
    }
    func rename(_ n: Notebook, _ title: String) throws { let old = n.title; try commitLibrary({ n.title = title }, revert: { n.title = old }) }
    func rename(_ s: Section, _ title: String) throws { let old = s.title; try commitLibrary({ s.title = title }, revert: { s.title = old }) }
    func rename(_ p: Page, _ title: String) throws { let old = p.title; try commitLibrary({ p.title = title }, revert: { p.title = old }) }
    func renamePage(_ title: String) throws { if let page { try rename(page, title) } }
    private func editAppearance(_ change: (inout JSON) -> Void) throws {
        guard canEdit, let page else { return }; try flush()
        let old = page.json
        let display = AppearanceBridge.display(page)
        var v = display.view; change(&v); display.view = v; AppearanceBridge.makeCanonical(display)
        page.json["appearance"] = display.json["appearance"]
        do { try store.saveLibrary(notebooks); changed?() } catch { page.json = old; throw error }
    }
    func setBackground(_ name: String) throws { try editAppearance { $0["backgroundFormat"] = .string(name) } }
    func setPaper(_ paper: String) throws { try editAppearance { $0["paperSize"] = .string(paper) } }
    func setTitleVisible(_ visible: Bool) throws { try editAppearance { $0["pageTitleVisible"] = .bool(visible) } }
    /// Clearing keeps immutable PDF page backgrounds, like the Windows/Android clear-page command.
    func clearPage() { mutate { d in d.strokes = []; d.texts = []; d.tags = []; d.objects = d.objects.filter { $0["isPageBackground"].bool() } } }
    func formatTexts(_ ids: Set<String>, _ change: (inout JSON) -> Void) {
        mutate { d in d.texts = d.texts.map { item in
            guard ids.contains(item["id"].string) else { return item }
            var value = item; change(&value); return value
        } }
    }
    // MARK: Hierarchy deletion
    /// The index commits first; page files then move to the macOS Trash so a mistaken delete is recoverable.
    func delete(_ p: Page, from s: Section, in n: Notebook) throws {
        guard let index = s.pages.firstIndex(where: { $0 === p }) else { return }
        try commitLibrary({ s.pages.remove(at: index) }, revert: { s.pages.insert(p, at: index) })
        finishDeletion(notebook: n.id, pages: [p.id], directory: false)
    }
    func delete(_ s: Section, in n: Notebook) throws {
        var restore: (() -> Void)?
        try commitLibrary({
            if let i = n.sections.firstIndex(where: { $0 === s }) { n.sections.remove(at: i); restore = { n.sections.insert(s, at: i) }; return }
            for parent in n.allSections { if let i = parent.children.firstIndex(where: { $0 === s }) { parent.children.remove(at: i); restore = { parent.children.insert(s, at: i) }; return } }
        }, revert: { restore?() })
        finishDeletion(notebook: n.id, pages: s.all.flatMap(\.pages).map(\.id), directory: false)
    }
    /// Deleting the last notebook leaves a fresh empty notebook, never the sample library.
    func delete(_ n: Notebook) throws {
        guard let index = notebooks.firstIndex(where: { $0 === n }) else { return }
        let replacement = notebooks.count == 1 ? Notebook(title: "我的笔记本", sections: [Section(title: "笔记")]) : nil
        try commitLibrary({ notebooks.remove(at: index); if let replacement { notebooks.append(replacement) } },
                          revert: { notebooks.removeAll { $0 === replacement }; notebooks.insert(n, at: index) })
        finishDeletion(notebook: n.id, pages: n.pages.map(\.id), directory: true)
    }
    private func finishDeletion(notebook id: String, pages: [String], directory: Bool) {
        if directory { store.trashNotebook(id) } else { store.trash(notebook: id, pages: pages) }
        for page in pages { histories[page] = nil }
        let liveSection = section.map { s in notebooks.contains { $0.allSections.contains { $0 === s } } } ?? false
        let livePage = page.map { p in notebooks.contains { $0.pages.contains { $0 === p } } } ?? liveSection
        if livePage && liveSection { changed?(); return }
        let n = notebooks.first { $0 === notebook } ?? notebooks.first
        let s = liveSection ? section : n?.allSections.first
        page = nil; document = nil; dirty = false
        do { if let n { try select(n, s, s?.pages.first) } } catch { onError?(error) }
        changed?()
    }
    func exchangeNotebook() throws -> Notebook {
        guard let notebook else { throw MojianError.invalid("请先打开笔记本") }; try flush()
        let copy = Notebook(notebook.json)
        for s in copy.allSections { s.pages = s.pages.map { AppearanceBridge.display($0) } }
        return copy
    }
    func importNotebook(_ url: URL) throws {
        guard !store.readOnly else { throw MojianError.invalid("请在本地可编辑笔记库中导入") }; try flush()
        let staging = URL(fileURLWithPath: "/tmp/.agents/mojian-macos-import-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: staging) }
        let stagedStore = try WorkspaceStore(root: staging)
        let imported = try NotebookArchive.importNotebook(from: url, store: stagedStore)
        for p in imported.pages {
            AppearanceBridge.makeCanonical(p)
            let d = try stagedStore.loadPage(notebook: imported.id, page: p.id)
            let dir = try stagedStore.assets(notebook: imported.id, page: p.id)
            if FileManager.default.fileExists(atPath: dir.path) {
                for asset in try FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) {
                    try store.writeAsset(Data(contentsOf: asset), notebook: imported.id, page: p.id, name: asset.lastPathComponent)
                }
            }
            try store.writePage(d, incrementRevision: false)
        }
        notebooks.append(imported)
        do { try store.saveLibrary(notebooks) } catch { notebooks.removeLast(); throw error }
        let s = imported.allSections.first; try select(imported, s, s?.pages.first)
    }
}

/// Queue-owned sequence avoids stale asynchronous save completions and duplicate close writes.
private final class DesktopWriter: @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.meta.mojian.macos.save", qos: .utility)
    private let store: CanonicalStore
    private var savedGeneration = 0
    init(store: CanonicalStore) { self.store = store }
    private func persist(_ document: PageDocument?, library: JSON?, generation: Int) throws {
        guard generation > savedGeneration else { return }
        if let document { _ = try store.writePage(document) }
        if let library { try store.saveLibrarySnapshot(library) }
        savedGeneration = generation
    }
    func enqueue(_ document: PageDocument, library: JSON?, generation: Int, completion: @escaping @MainActor @Sendable (Result<Void, Error>) -> Void) {
        queue.async {
            let result = Result { try self.persist(document, library: library, generation: generation) }
            DispatchQueue.main.async { completion(result) }
        }
    }
    func flush(_ document: PageDocument?, library: JSON?, generation: Int) throws {
        try queue.sync { try persist(document, library: library, generation: generation) }
    }
}
