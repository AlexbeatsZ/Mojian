import Foundation
import Darwin

/// Canonical v2 folders. Existing external folders are opened without creating or rewriting files.
public final class CanonicalStore: @unchecked Sendable {
    private let mutex = NSRecursiveLock()
    public let root: URL
    public let readOnly: Bool
    private var recovered = [String]()
    public var recoveredFiles: [String] { mutex.lock(); defer { mutex.unlock() }; return recovered }
    private var lockDescriptor: Int32 = -1
    private var observed = [String: Stamp]()
    private var pageRevisions = [String: Double]()
    private let repairOnRead: Bool
    private var validated = [String: Stamp]()
    private var cachedJSON = [String: JSON]()
    private func cache(_ json: JSON, relative: String) {
        if relative.hasPrefix("notebooks/") {
            cachedJSON = cachedJSON.filter { !$0.key.hasPrefix("notebooks/") || $0.key == relative }
        }
        cachedJSON[relative] = json
    }
    private var librarySource: JSON = .object([:])
    private let fm = FileManager.default
    /// Removal of unreferenced page files; tests substitute a private folder for the user's Trash.
    public var discard: (URL) throws -> Void = { url in
        #if os(macOS)
        try FileManager.default.trashItem(at: url, resultingItemURL: nil)
        #else
        throw MojianError.invalid("请使用笔记库恢复目录保存删除内容")
        #endif
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
    public init(root: URL, create: Bool = false, writable: Bool = false, owner: String = "macos") throws {
        self.root = root.standardizedFileURL.resolvingSymlinksInPath()
        repairOnRead = owner == "archive"
        readOnly = !create && !writable
        if create {
            if fm.fileExists(atPath: self.root.path) {
                guard try fm.contentsOfDirectory(atPath: self.root.path).isEmpty else { throw MojianError.invalid("请选择空文件夹创建笔记库") }
            } else { try fm.createDirectory(at: self.root, withIntermediateDirectories: true) }
        } else {
            guard fm.fileExists(atPath: self.root.appendingPathComponent("library.json").path) || fm.fileExists(atPath: self.root.appendingPathComponent("library.json.previous").path) else { throw MojianError.invalid("所选文件夹不是墨笺笔记库") }
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
            try Data("Mojian local library v2\n".utf8).write(to: checked(".mojian-\(owner)-library"), options: .atomic)
            let n = Notebook(sections: [Section(title: "笔记", pages: [Self.newPage(title: "开始书写")])])
            librarySource = .object(["version": .number(2), "notebooks": .array([n.json])])
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
    public func checked(_ relative: String) throws -> URL {
        mutex.lock(); defer { mutex.unlock() }
        let parts = relative.split(separator: "/", omittingEmptySubsequences: false)
        guard !parts.isEmpty, !relative.hasPrefix("/"), !relative.contains("\\") else { throw MojianError.invalid("无效路径") }
        var url = root
        for part in parts {
            _ = try WorkspaceStore.safeComponent(String(part)); url.appendPathComponent(String(part))
            if let attrs = try? fm.attributesOfItem(atPath: url.path), attrs[.type] as? FileAttributeType == .typeSymbolicLink {
                throw MojianError.invalid("笔记库不允许符号链接：\(relative)")
            }
        }
        // Validated path components cannot leave root. Existing symlinks are rejected above.
        return url
    }
    private func writable() throws { if readOnly { throw MojianError.invalid("当前笔记库为只读") } }
    private func read(_ relative: String, validate: (JSON) throws -> Void) throws -> JSON {
        let url = try checked(relative), previous = try checked(relative + ".previous")
        let exists = fm.fileExists(atPath: url.path)
        observed[relative] = exists ? try Stamp(url) : nil
        if exists {
            // Read errors propagate; only invalid data may recover from the previous revision.
            let data = try Data(contentsOf: url)
            do {
                let j = try JSON.read(data); try validate(j)
                validated[relative] = observed[relative]; cache(j, relative: relative)
                return j
            }
            catch {
                guard fm.fileExists(atPath: previous.path) else { throw error }
                let j = try JSON.read(Data(contentsOf: previous)); try validate(j)
                if !readOnly {
                    let recovery = try checked(".mojian-corrupt.previous/\(UUID().uuidString)/\(relative)")
                    try fm.createDirectory(at: recovery.deletingLastPathComponent(), withIntermediateDirectories: true)
                    try atomic(data, to: recovery)
                    if repairOnRead {
                        try atomic(j.data(), to: url)
                        observed[relative] = try Stamp(url); validated[relative] = observed[relative]
                    }
                }
                cache(j, relative: relative); recovered.append(relative); return j
            }
        }
        let j = try JSON.read(Data(contentsOf: previous)); try validate(j)
        if !readOnly && repairOnRead {
            try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try atomic(j.data(), to: url)
            observed[relative] = try Stamp(url); validated[relative] = observed[relative]
        }
        cache(j, relative: relative); recovered.append(relative); return j
    }
    private func write(_ json: JSON, relative: String, validate: (JSON) throws -> Void) throws {
        try writable(); try validate(json)
        let url = try checked(relative)
        let stamp = fm.fileExists(atPath: url.path) ? try Stamp(url) : nil
        guard observed[relative] == stamp else { throw MojianError.invalid("文件已被外部修改，已阻止覆盖。请重新打开笔记库。") }
        if stamp != nil, validated[relative] == stamp, cachedJSON[relative] == json { return }
        let data = try json.data()
        try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if stamp != nil {
            let old = try Data(contentsOf: url)
            if validated[relative] == stamp {
                try atomic(old, to: try checked(relative + ".previous"))
            }
        }
        try atomic(data, to: url); observed[relative] = try Stamp(url)
        validated[relative] = observed[relative]; cache(json, relative: relative)
    }
    private func atomic(_ data: Data, to url: URL) throws {
        let temp = url.deletingLastPathComponent().appendingPathComponent(".macos-\(UUID().uuidString).tmp")
        defer { try? fm.removeItem(at: temp) }
        guard fm.createFile(atPath: temp.path, contents: nil, attributes: [.posixPermissions: 0o600]) else { throw MojianError.invalid("无法创建保存临时文件") }
        let handle = try FileHandle(forWritingTo: temp)
        do { try handle.write(contentsOf: data); try handle.synchronize(); try handle.close() }
        catch { try? handle.close(); throw error }
        guard Darwin.rename(temp.path, url.path) == 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
    }
    private static func validateLibrary(_ j: JSON) throws {
        guard [1.0, 2.0].contains(j["version"].number()), case .array(let books) = j["notebooks"] else { throw MojianError.invalid("笔记库索引格式或版本无效") }
        var identifiers = Set<String>()
        func id(_ j: JSON) throws {
            guard case .object = j else { throw MojianError.invalid("索引节点格式无效") }
            _ = try WorkspaceStore.safe(j["id"].string)
            guard identifiers.insert(j["id"].string).inserted else { throw MojianError.invalid("索引中存在重复标识") }
        }
        func sections(_ value: JSON, depth: Int) throws {
            guard depth < 100, case .array(let nodes) = value else { throw MojianError.invalid("分区结构无效或嵌套过深") }
            for s in nodes {
                try id(s)
                guard case .array(let pages) = s["pages"] else { throw MojianError.invalid("页面列表格式无效") }
                for page in pages { try id(page) }
                if s["children"] != .null { try sections(s["children"], depth: depth + 1) }
            }
        }
        for book in books { try id(book); try sections(book["sections"], depth: 0) }
    }
    public func loadLibrary() throws -> [Notebook] {
        mutex.lock(); defer { mutex.unlock() }
        librarySource = try read("library.json", validate: Self.validateLibrary)
        return librarySource["notebooks"].array.map(Notebook.init)
    }
    public func saveLibrary(_ notebooks: [Notebook]) throws {
        mutex.lock(); defer { mutex.unlock() }
        try saveLibrarySnapshot(.array(notebooks.map(\.json)))
    }
    public func saveLibrarySnapshot(_ notebooks: JSON) throws {
        mutex.lock(); defer { mutex.unlock() }
        var j = librarySource
        j["version"] = .number(2)
        j["notebooks"] = .array(notebooks.array.map { notebook in
            let old = librarySource["notebooks"].array.first { $0["id"] == notebook["id"] } ?? .object([:])
            return Self.preserveNavigation(notebook, previous: old, notebook: true)
        })
        try write(j, relative: "library.json", validate: Self.validateLibrary); librarySource = j
    }
    private static func preserveNavigation(_ source: JSON, previous: JSON, notebook: Bool) -> JSON {
        var values = source.dictionary
        let fields = notebook ? ["expanded", "focusedSectionId", "selectedPageSectionId", "selectedPageId"] : ["expanded"]
        for field in fields {
            if let old = previous.dictionary[field] { values[field] = old }
            else { values.removeValue(forKey: field) }
        }
        let key = notebook ? "sections" : "children"
        if source[key] != .null {
            values[key] = .array(source[key].array.map { section in
                let old = previous[key].array.first { $0["id"] == section["id"] } ?? .object([:])
                return preserveNavigation(section, previous: old, notebook: false)
            })
        }
        return .object(values)
    }

    public func loadPage(notebook: String, page: String) throws -> PageDocument {
        mutex.lock(); defer { mutex.unlock() }
        let relative = "notebooks/\(try WorkspaceStore.safe(notebook))/pages/\(try WorkspaceStore.safe(page)).json"
        let j = try read(relative) { j in
            let d = try PageDocument(j)
            guard d.pageID == page, d.notebookID == notebook else { throw MojianError.invalid("页面标识与索引不匹配") }
        }
        pageRevisions["\(notebook)/\(page)"] = j["revision"].number()
        return try PageDocument(j)
    }
    @discardableResult public func writePage(_ document: PageDocument, incrementRevision: Bool = true) throws -> PageDocument {
        mutex.lock(); defer { mutex.unlock() }
        let relative = "notebooks/\(try WorkspaceStore.safe(document.notebookID))/pages/\(try WorkspaceStore.safe(document.pageID)).json"
        try writable()
        let url = try checked(relative)
        let current = fm.fileExists(atPath: url.path) ? try Stamp(url) : nil
        guard observed[relative] == current else { throw MojianError.invalid("文件已被外部修改，已阻止覆盖。请重新打开笔记库。") }
        if incrementRevision, validated[relative] == current, let previous = cachedJSON[relative] {
            func content(_ json: JSON) -> JSON { .object(json.dictionary.filter { $0.key != "revision" && $0.key != "updatedAt" }) }
            if content(previous) == content(document.json) { return try PageDocument(previous) }
        }
        var d = document
        if incrementRevision {
            d.json["revision"] = .number(max(d.json["revision"].number(), pageRevisions["\(d.notebookID)/\(d.pageID)"] ?? 0) + 1)
            d.json["updatedAt"] = .string(ISO8601DateFormatter().string(from: Date()))
        }
        try write(d.json, relative: relative) { _ = try PageDocument($0) }; pageRevisions["\(d.notebookID)/\(d.pageID)"] = d.json["revision"].number(); return d
    }
    public func writeAsset(_ data: Data, notebook: String, page: String, name: String) throws {
        mutex.lock(); defer { mutex.unlock() }
        try writable()
        let url = try checked("notebooks/\(WorkspaceStore.safe(notebook))/assets/\(WorkspaceStore.safe(page))/\(WorkspaceStore.safe(name))")
        guard !fm.fileExists(atPath: url.path) else { throw MojianError.invalid("资源文件已存在") }
        try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try atomic(data, to: url)
    }
    /// Called only after the index no longer references these pages. Files go to the macOS Trash
    /// (recoverable); a failed move leaves an unreferenced orphan rather than a dangling index entry.
    @discardableResult public func trash(notebook: String, pages: [String]) -> [String] {
        mutex.lock(); defer { mutex.unlock() }
        guard !readOnly, let nb = try? WorkspaceStore.safe(notebook) else { return pages }
        return pages.filter { id in
            guard let page = try? WorkspaceStore.safe(id) else { return true }
            let moved = ["notebooks/\(nb)/pages/\(page).json", "notebooks/\(nb)/pages/\(page).json.previous", "notebooks/\(nb)/assets/\(page)"].map(moveToTrash)
            return moved.contains(false)
        }
    }
    @discardableResult public func trashNotebook(_ notebook: String) -> Bool {
        mutex.lock(); defer { mutex.unlock() }
        guard !readOnly, let nb = try? WorkspaceStore.safe(notebook) else { return false }
        return moveToTrash("notebooks/\(nb)")
    }
    private func moveToTrash(_ relative: String) -> Bool {
        guard let url = try? checked(relative) else { return false }
        guard fm.fileExists(atPath: url.path) else { return true }
        do { try discard(url) } catch { return false }
        observed = observed.filter { !$0.key.hasPrefix(relative) }
        return true
    }
    public func assetURL(_ name: String, document: PageDocument, owner: String? = nil) throws -> URL {
        mutex.lock(); defer { mutex.unlock() }
        return try checked("notebooks/\(WorkspaceStore.safe(document.notebookID))/assets/\(WorkspaceStore.safe(owner ?? document.pageID))/\(WorkspaceStore.safe(name))")
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
