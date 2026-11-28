import Foundation
import Darwin

/// The v3 folder library (docs/design/folder-library.md): notebooks and sections are folders, pages `<title>.mojian`,
/// assets `.mojian/assets/<page>`. Existing external folders are opened without creating or rewriting files.
public final class CanonicalStore: @unchecked Sendable {
    private let mutex = NSRecursiveLock()
    public let root: URL
    public let readOnly: Bool
    public let folder: FolderLibrary
    private var recovered = [String]()
    public var recoveredFiles: [String] { mutex.lock(); defer { mutex.unlock() }; return recovered + folder.recovered }
    private var lockDescriptor: Int32 = -1
    /// Page id -> stamp of the bytes this store last read or wrote (absent: never read).
    private var observed = [String: Stamp?]()
    private var validated = [String: Stamp]()
    private var cachedContent = [String: JSON]()
    private var pageRevisions = [String: Double]()
    private var librarySource: JSON = .object(["version": .number(2), "notebooks": .array([])])
    private var librarySignature: String?
    private let fm = FileManager.default
    /// Removal of pages, assets and folders the library no longer lists; tests substitute a private folder for the user's Trash.
    public var discard: (URL) throws -> Void {
        get { folder.discard }
        set { folder.discard = newValue }
    }
    private struct Stamp: Equatable {
        var size: UInt64, modified: Date, inode: UInt64
        init(_ url: URL) throws {
            let a = try FileManager.default.attributesOfItem(atPath: url.path)
            size = (a[.size] as? NSNumber)?.uint64Value ?? 0
            modified = a[.modificationDate] as? Date ?? .distantPast
            inode = (a[.systemFileNumber] as? NSNumber)?.uint64Value ?? 0
        }
    }
    private func stamp(_ url: URL) throws -> Stamp? { fm.fileExists(atPath: url.path) ? try Stamp(url) : nil }

    public init(root: URL, create: Bool = false, writable: Bool = false, owner: String = "macos") throws {
        self.root = root.standardizedFileURL.resolvingSymlinksInPath()
        readOnly = !create && !writable
        folder = FolderLibrary(root: self.root, readOnly: readOnly)
        #if os(macOS)
        folder.discard = { try FileManager.default.trashItem(at: $0, resultingItemURL: nil) }
        #endif
        if create {
            if fm.fileExists(atPath: self.root.path) {
                guard try fm.contentsOfDirectory(atPath: self.root.path).isEmpty else { throw MojianError.invalid("请选择空文件夹创建笔记库") }
            } else { try fm.createDirectory(at: self.root, withIntermediateDirectories: true) }
        } else {
            guard FolderLibrary.isLibrary(self.root) else { throw MojianError.invalid("所选文件夹不是墨笺笔记库") }
        }
        if !readOnly {
            guard ![".stfolder", ".mojian-sync", ".mojian-native-workspace"].contains(where: { fm.fileExists(atPath: self.root.appendingPathComponent($0).path) }) else {
                throw MojianError.invalid("此文件夹由同步管理。当前 Mac 基线只允许只读打开。")
            }
            if !create {
                guard owner == "archive" || fm.fileExists(atPath: self.root.appendingPathComponent(".mojian-macos-library").path) else { throw MojianError.invalid("外部笔记库请以只读方式打开") }
            }
            let lockURL = try checked(".mojian-macos.lock")
            lockDescriptor = Darwin.open(lockURL.path, O_CREAT | O_RDWR | O_NOFOLLOW, S_IRUSR | S_IWUSR)
            guard lockDescriptor >= 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
            guard flock(lockDescriptor, LOCK_EX | LOCK_NB) == 0 else {
                close(lockDescriptor); lockDescriptor = -1
                throw MojianError.invalid("此笔记库已在另一个墨笺进程中打开")
            }
        }
        if create {
            try Data("Mojian local library v3\n".utf8).write(to: checked(".mojian-\(owner)-library"), options: .atomic)
            try fm.createDirectory(at: checked(FolderNames.hiddenRoot), withIntermediateDirectories: true)
            librarySignature = folder.signature()
            let n = Notebook(sections: [Section(title: "笔记", pages: [Self.newPage(title: "开始书写")])])
            for page in n.pages { try writePage(PageDocument(notebook: n.id, page: page.id), incrementRevision: false) }
            try saveLibrary([n])
        }
    }
    deinit { if lockDescriptor >= 0 { flock(lockDescriptor, LOCK_UN); close(lockDescriptor) } }
    public static func newPage(title: String, paper: String = "BOUNDLESS", background: String = "GRID_STANDARD") -> Page {
        let page = Page(title: title)
        var view = Page.defaultView
        view["paperSize"] = .string(paper); view["backgroundFormat"] = .string(background)
        page.view = view; AppearanceBridge.makeCanonical(page)
        return page
    }
    /// A path below the library root; folder and page names are titles, so any storable name is accepted.
    public func checked(_ relative: String) throws -> URL {
        mutex.lock(); defer { mutex.unlock() }
        let parts = relative.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard !parts.isEmpty, !relative.hasPrefix("/"), !relative.contains("\\") else { throw MojianError.invalid("无效路径") }
        var url = root
        for part in parts {
            guard !part.isEmpty, part != ".", part != "..", !part.contains("\0"), part.utf8.count < 256 else { throw MojianError.invalid("无效路径") }
            url.appendPathComponent(part)
            if let attrs = try? fm.attributesOfItem(atPath: url.path), attrs[.type] as? FileAttributeType == .typeSymbolicLink {
                throw MojianError.invalid("笔记库不允许符号链接：\(relative)")
            }
        }
        return url
    }
    private func writable() throws { if readOnly { throw MojianError.invalid("当前笔记库为只读") } }

    public func loadLibrary() throws -> [Notebook] {
        mutex.lock(); defer { mutex.unlock() }
        librarySource = try folder.read()
        librarySignature = folder.signature()
        return librarySource["notebooks"].array.map(Notebook.init)
    }
    /// True when another program (a sync receiver, a file manager) changed folders, page names or descriptors.
    public var libraryChanged: Bool {
        mutex.lock(); defer { mutex.unlock() }
        return librarySignature.map { $0 != folder.signature() } ?? false
    }
    public func saveLibrary(_ notebooks: [Notebook]) throws {
        mutex.lock(); defer { mutex.unlock() }
        try saveLibrarySnapshot(.array(notebooks.map(\.json)))
    }
    private static let navigation: Set<String> = ["expanded", "focusedSectionId", "selectedPageSectionId", "selectedPageId", "pagePaneMode", "activeNotebookId"]
    /// Synchronized structure only: navigation is device-local and never makes the library "changed".
    private static func shared(_ json: JSON) -> JSON {
        switch json {
        case .object(let values): return .object(values.filter { !navigation.contains($0.key) }.mapValues(shared))
        case .array(let items): return .array(items.map(shared))
        default: return json
        }
    }
    public func saveLibrarySnapshot(_ notebooks: JSON) throws {
        mutex.lock(); defer { mutex.unlock() }
        try writable()
        var next = librarySource
        next["version"] = .number(2); next["notebooks"] = notebooks
        guard Self.shared(next) != Self.shared(librarySource) else { return }
        guard !libraryChanged else { throw MojianError.invalid("笔记库已被外部修改，已阻止覆盖。请重新打开笔记库。") }
        try folder.apply(next, beforeWrite: { id, url in
            if let expected = self.observed[id] { guard try self.stamp(url) == expected else { throw MojianError.invalid("文件已被外部修改，已阻止覆盖。请重新打开笔记库。") } }
        }, afterWrite: { id, url in
            // This store's own head rewrite or move: the page bytes stay the version it read.
            if self.observed[id] != nil {
                let current = try Stamp(url)
                if self.validated[id] == self.observed[id] ?? nil { self.validated[id] = current }
                self.observed[id] = current
            }
        })
        librarySource = next
        librarySignature = folder.signature()
    }

    /// The file holding a page: its library file, or its staged content before the page joins the library.
    public func pageURL(notebook: String, page: String) throws -> URL {
        mutex.lock(); defer { mutex.unlock() }
        return try folder.pageURL(page) ?? folder.pendingURL(page)
    }
    private func content(of json: JSON, notebook: String, page: String) throws -> JSON {
        // Assigning a key would turn any non-object (`[]`, a string) into an empty page instead of recovering it.
        guard case .object = json else { throw MojianError.invalid("页面格式无效或版本不受支持") }
        var content = json["version"].number() == 3 && json["content"] != .null ? json["content"] : json
        content["notebookId"] = .string(notebook); content["pageId"] = .string(page)
        if content["version"] == .null { content["version"] = .number(2) }
        return content
    }
    public func loadPage(notebook: String, page: String) throws -> PageDocument {
        mutex.lock(); defer { mutex.unlock() }
        _ = try WorkspaceStore.safe(notebook); _ = try WorkspaceStore.safe(page)
        let url = try pageURL(notebook: notebook, page: page)
        guard fm.fileExists(atPath: url.path) else { throw MojianError.invalid("页面文件缺失：\(folder.relative(url))") }
        let current = try Stamp(url)
        // Read errors propagate; only invalid data may recover from the previous revision.
        let data = try Data(contentsOf: url)
        var json: JSON
        do {
            json = try content(of: try JSON.read(data), notebook: notebook, page: page); _ = try PageDocument(json)
            validated[page] = current
        } catch {
            let previous = folder.previousURL(url)
            guard fm.fileExists(atPath: previous.path) else { throw error }
            json = try content(of: try JSON.read(Data(contentsOf: previous)), notebook: notebook, page: page); _ = try PageDocument(json)
            if !readOnly {
                let recovery = try checked(".mojian-corrupt.previous/\(UUID().uuidString)/\(folder.relative(url))")
                try fm.createDirectory(at: recovery.deletingLastPathComponent(), withIntermediateDirectories: true)
                try data.write(to: recovery)
            }
            validated.removeValue(forKey: page)
            recovered.append(folder.relative(url))
        }
        observed[page] = current
        cachedContent[page] = json
        pageRevisions["\(notebook)/\(page)"] = json["revision"].number()
        return try PageDocument(json)
    }
    @discardableResult public func writePage(_ document: PageDocument, incrementRevision: Bool = true) throws -> PageDocument {
        mutex.lock(); defer { mutex.unlock() }
        try writable()
        _ = try WorkspaceStore.safe(document.notebookID); let page = try WorkspaceStore.safe(document.pageID)
        let url = try pageURL(notebook: document.notebookID, page: page)
        let current = try stamp(url)
        guard (observed[page] ?? nil) == current else { throw MojianError.invalid("文件已被外部修改，已阻止覆盖。请重新打开笔记库。") }
        // Disk form packs eligible point arrays (shared/ink-points.md); the returned document is expanded.
        let packed = InkPoints.pack(document.json)
        if incrementRevision, current != nil, validated[page] == current, let previous = cachedContent[page] {
            func body(_ json: JSON) -> JSON { .object(json.dictionary.filter { $0.key != "revision" && $0.key != "updatedAt" }) }
            if body(previous) == body(packed) { return try PageDocument(previous) }
        }
        var d = document
        d.json = packed
        if incrementRevision {
            d.json["revision"] = .number(max(d.json["revision"].number(), pageRevisions["\(d.notebookID)/\(page)"] ?? 0) + 1)
            d.json["updatedAt"] = .string(ISO8601DateFormatter().string(from: Date()))
        }
        _ = try PageDocument(d.json)
        let contentData = try d.json.data()
        let data: Data
        if current != nil, folder.index.pages[page] != nil {
            // Content goes under the head bytes as stored; head edits happen only through the library.
            // A corrupt current file takes its head from the previous version, as the library listing did.
            let previous = folder.previousURL(url)
            if let prefix = PageFileFormat.headPrefix(try Data(contentsOf: url)) ?? (try? Data(contentsOf: previous)).flatMap(PageFileFormat.headPrefix) {
                data = PageFileFormat.recompose(prefix: prefix, content: contentData)
            } else {
                let head = (try? PageFileFormat.readHead(url)) ?? (try? PageFileFormat.readHead(previous))
                data = try PageFileFormat.compose(id: page, sortKey: head?.sortKey ?? folder.index.keys[page] ?? "a0", page: head?.page ?? .object([:]), content: contentData)
            }
        } else { data = contentData }
        // A corrupt current file is never rotated over the valid previous version it was recovered from.
        try folder.atomic(data, to: url, keepPrevious: current == nil || validated[page] == current)
        let written = try Stamp(url)
        observed[page] = written; validated[page] = written; cachedContent[page] = d.json
        pageRevisions["\(d.notebookID)/\(page)"] = d.json["revision"].number()
        return try PageDocument(d.json)
    }
    public func writeAsset(_ data: Data, notebook: String, page: String, name: String) throws {
        mutex.lock(); defer { mutex.unlock() }
        try writable()
        _ = try WorkspaceStore.safe(notebook)
        let url = try folder.assetDirectory(page).appendingPathComponent(WorkspaceStore.safe(name))
        _ = try checked(folder.relative(url))
        guard !fm.fileExists(atPath: url.path) else { throw MojianError.invalid("资源文件已存在") }
        try folder.atomic(data, to: url, keepPrevious: false)
    }
    /// Removes staged content and assets of pages the library no longer lists (listed pages are removed by
    /// `saveLibrary`). Files go to the macOS Trash (recoverable); a failed move leaves an unreferenced orphan.
    @discardableResult public func trash(notebook: String, pages: [String]) -> [String] {
        mutex.lock(); defer { mutex.unlock() }
        guard !readOnly else { return pages }
        return pages.filter { id in
            guard (try? WorkspaceStore.safe(id)) != nil, folder.index.pages[id] == nil,
                  let pending = try? folder.pendingURL(id), let assets = try? folder.assetDirectory(id) else { return true }
            let moved = [pending, folder.previousURL(pending), assets].map(moveToTrash)
            observed.removeValue(forKey: id); validated.removeValue(forKey: id); cachedContent.removeValue(forKey: id)
            return moved.contains(false)
        }
    }
    /// The notebook folder itself is removed by `saveLibrary`; nothing else remains to clean.
    @discardableResult public func trashNotebook(_ notebook: String) -> Bool {
        mutex.lock(); defer { mutex.unlock() }
        return !readOnly && folder.index.folders[notebook] == nil
    }
    private func moveToTrash(_ url: URL) -> Bool {
        guard fm.fileExists(atPath: url.path) else { return true }
        do { try discard(url) } catch { return false }
        return true
    }
    public func assetURL(_ name: String, document: PageDocument, owner: String? = nil) throws -> URL {
        mutex.lock(); defer { mutex.unlock() }
        let url = try folder.assetDirectory(owner ?? document.pageID).appendingPathComponent(WorkspaceStore.safe(name))
        return try checked(folder.relative(url))
    }
    public func assetDirectory(page: String) throws -> URL {
        mutex.lock(); defer { mutex.unlock() }
        return try folder.assetDirectory(page)
    }
}

public enum AppearanceBridge {
    public static let backgroundNames = ["BLANK", "RULE_NARROW", "RULE_STANDARD", "RULE_WIDE", "GRID_DENSE", "GRID_STANDARD", "GRID_WIDE", "DOTS_DENSE", "DOTS_STANDARD", "DOTS_WIDE", "RULE_TWO_COLUMN_STANDARD", "RULE_TWO_COLUMN_NARROW", "RULE_TWO_COLUMN_WIDE", "RULE_CLEAN"]

    public static func view(_ appearance: JSON) -> JSON {
        var view = Page.defaultView
        let kind = Int(appearance["paperKind"].number())
        view["paperSize"] = .string(kind == 2 ? "PDF" : kind == 1 ? "A4" : "BOUNDLESS")
        let background = Int(appearance["background"].number(5))
        view["backgroundFormat"] = .string(backgroundNames[min(backgroundNames.count - 1, max(0, background))])
        view["darkBackground"] = .bool(appearance["theme"].number() == 0)
        view["pageTitleVisible"] = .bool(appearance["showTitle"].bool(true))
        view["a4PageCount"] = .number(max(1, appearance["sheetCount"].number(1)))
        view["customDocumentWidth"] = .number(appearance["width"].number(793.7))
        view["customDocumentHeight"] = .number(appearance["height"].number(1122.5))
        view["boundlessDocumentWidth"] = .number(appearance["infiniteWidth"].number(793.7) * androidUnitsPerUnit)
        view["boundlessDocumentHeight"] = .number(appearance["infiniteHeight"].number(1122.5) * androidUnitsPerUnit)
        return view
    }

    public static func appearance(_ view: JSON, original: JSON) -> JSON {
        var result = original == .null ? JSON.object([:]) : original
        let previous = Self.view(original)
        if original == .null || previous["paperSize"] != view["paperSize"] {
            result["paperKind"] = .number(view["paperSize"].string == "PDF" ? 2 : view["paperSize"].string == "A4" ? 1 : 0)
        }
        if original == .null || previous["backgroundFormat"] != view["backgroundFormat"] {
            result["background"] = .number(Double(backgroundNames.firstIndex(of: view["backgroundFormat"].string) ?? 5))
        }
        result["theme"] = .number(view["darkBackground"].bool(true) ? 0 : 1)
        result["showTitle"] = .bool(view["pageTitleVisible"].bool(true))
        result["sheetCount"] = .number(view["a4PageCount"].number(1))
        result["width"] = .number(view["customDocumentWidth"].number(793.7))
        result["height"] = .number(view["customDocumentHeight"].number(1122.5))
        result["infiniteWidth"] = .number(view["boundlessDocumentWidth"].number(793.7 * androidUnitsPerUnit) / androidUnitsPerUnit)
        result["infiniteHeight"] = .number(view["boundlessDocumentHeight"].number(1122.5 * androidUnitsPerUnit) / androidUnitsPerUnit)
        if result["pageColor"] == .null { result["pageColor"] = .string(view["darkBackground"].bool(true) ? "#1B1B1B" : "#FAFAFA") }
        return result
    }

    public static func display(_ source: Page, dark: Bool? = nil) -> Page {
        let copy = Page(source.json)
        if let dark { var view = copy.view; view["darkBackground"] = .bool(dark); copy.view = view }
        return copy
    }

    public static func makeCanonical(_ page: Page) {
        let view = page.view
        page.json["appearance"] = appearance(view, original: page.json["appearance"])
        // Newly authored pages use a single canonical representation. Historical extra data
        // is retained by load/save, including legacy viewSettings already present in old files.
        page.json = .object(page.json.dictionary.filter { $0.key != "viewSettings" })
    }
}
