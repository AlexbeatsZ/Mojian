import Foundation
import Darwin
import MojianCore

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
    private var librarySource: JSON = .object([:])
    private let fm = FileManager.default
    private struct Stamp: Equatable {
        var size: UInt64, modified: Date, inode: UInt64
        init(_ url: URL) throws {
            let a = try FileManager.default.attributesOfItem(atPath: url.path)
            size = (a[.size] as? NSNumber)?.uint64Value ?? 0
            modified = a[.modificationDate] as? Date ?? .distantPast
            inode = (a[.systemFileNumber] as? NSNumber)?.uint64Value ?? 0
        }
    }
    public init(root: URL, create: Bool = false, writable: Bool = false) throws {
        self.root = root.standardizedFileURL.resolvingSymlinksInPath()
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
                guard fm.fileExists(atPath: self.root.appendingPathComponent(".mojian-macos-library").path) else { throw MojianError.invalid("外部笔记库请以只读方式打开") }
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
            try Data("Mojian macOS local library v1\n".utf8).write(to: checked(".mojian-macos-library"), options: .atomic)
            let n = Notebook(sections: [Section(title: "笔记", pages: [Self.newPage(title: "开始书写")])])
            librarySource = .object(["version": .number(2), "notebooks": .array([n.json]), "activeNotebookId": .string(n.id)])
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
            _ = try WorkspaceStore.safe(String(part)); url.appendPathComponent(String(part))
            if let attrs = try? fm.attributesOfItem(atPath: url.path), attrs[.type] as? FileAttributeType == .typeSymbolicLink {
                throw MojianError.invalid("笔记库不允许符号链接：\(relative)")
            }
        }
        guard url.resolvingSymlinksInPath().path.hasPrefix(root.path + "/") else { throw MojianError.invalid("路径超出笔记库") }
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
            do { let j = try JSON.read(data); try validate(j); return j }
            catch {
                guard fm.fileExists(atPath: previous.path) else { throw error }
                let j = try JSON.read(Data(contentsOf: previous)); try validate(j)
                recovered.append(relative); return j
            }
        }
        let j = try JSON.read(Data(contentsOf: previous)); try validate(j)
        recovered.append(relative); return j
    }
    private func write(_ json: JSON, relative: String, validate: (JSON) throws -> Void) throws {
        try writable(); try validate(json)
        let url = try checked(relative)
        let stamp = fm.fileExists(atPath: url.path) ? try Stamp(url) : nil
        guard observed[relative] == stamp else { throw MojianError.invalid("文件已被外部修改，已阻止覆盖。请重新打开笔记库。") }
        let data = try json.data()
        if stamp != nil, try Data(contentsOf: url) == data { return }
        try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if stamp != nil {
            let old = try Data(contentsOf: url)
            if let parsed = try? JSON.read(old), (try? validate(parsed)) != nil {
                try atomic(old, to: try checked(relative + ".previous"))
            }
        }
        try atomic(data, to: url); observed[relative] = try Stamp(url)
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
    func saveLibrarySnapshot(_ notebooks: JSON) throws {
        mutex.lock(); defer { mutex.unlock() }
        var j = librarySource
        j["notebooks"] = notebooks
        try write(j, relative: "library.json", validate: Self.validateLibrary); librarySource = j
    }
    public func loadPage(notebook: String, page: String) throws -> PageDocument {
        mutex.lock(); defer { mutex.unlock() }
        let relative = "notebooks/\(try WorkspaceStore.safe(notebook))/pages/\(try WorkspaceStore.safe(page)).json"
        let j = try read(relative) { j in
            let d = try PageDocument(j)
            guard d.pageID == page, d.notebookID == notebook else { throw MojianError.invalid("页面标识与索引不匹配") }
        }
        pageRevisions[page] = j["revision"].number()
        return try PageDocument(j)
    }
    @discardableResult public func writePage(_ document: PageDocument, incrementRevision: Bool = true) throws -> PageDocument {
        mutex.lock(); defer { mutex.unlock() }
        var d = document
        if incrementRevision {
            d.json["revision"] = .number(max(d.json["revision"].number(), pageRevisions[d.pageID] ?? 0) + 1)
            d.json["updatedAt"] = .string(ISO8601DateFormatter().string(from: Date()))
        }
        let relative = "notebooks/\(try WorkspaceStore.safe(d.notebookID))/pages/\(try WorkspaceStore.safe(d.pageID)).json"
        try write(d.json, relative: relative) { _ = try PageDocument($0) }; pageRevisions[d.pageID] = d.json["revision"].number(); return d
    }
    public func writeAsset(_ data: Data, notebook: String, page: String, name: String) throws {
        mutex.lock(); defer { mutex.unlock() }
        try writable()
        let url = try checked("notebooks/\(WorkspaceStore.safe(notebook))/assets/\(WorkspaceStore.safe(page))/\(WorkspaceStore.safe(name))")
        guard !fm.fileExists(atPath: url.path) else { throw MojianError.invalid("资源文件已存在") }
        try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try atomic(data, to: url)
    }
    public func assetURL(_ name: String, document: PageDocument, owner: String? = nil) throws -> URL {
        mutex.lock(); defer { mutex.unlock() }
        return try checked("notebooks/\(WorkspaceStore.safe(document.notebookID))/assets/\(WorkspaceStore.safe(owner ?? document.pageID))/\(WorkspaceStore.safe(name))")
    }
}

public enum AppearanceBridge {
    public static let backgroundNames = ["BLANK", "RULE_NARROW", "RULE_STANDARD", "RULE_WIDE", "GRID_DENSE", "GRID_STANDARD", "GRID_WIDE", "DOTS_DENSE", "DOTS_STANDARD", "DOTS_WIDE", "RULE_TWO_COLUMN_STANDARD", "RULE_TWO_COLUMN_NARROW", "RULE_TWO_COLUMN_WIDE", "RULE_CLEAN"]
    /// Display copies keep all source appearance bytes logically unchanged until an actual page-setting edit.
    public static func display(_ source: Page, dark: Bool? = nil) -> Page {
        let p = Page(source.json), a = source.json["appearance"]
        if a != .null {
            var v = Page.defaultView
            let kind = Int(a["paperKind"].number())
            v["paperSize"] = .string(kind == 2 ? "PDF" : kind == 1 ? "A4" : "BOUNDLESS")
            v["backgroundFormat"] = .string(backgroundNames[min(13, max(0, Int(a["background"].number(5))))])
            v["darkBackground"] = .bool(a["theme"].number() == 0)
            v["pageTitleVisible"] = a["showTitle"] == .null ? .bool(true) : a["showTitle"]
            v["a4PageCount"] = .number(max(1, a["sheetCount"].number(1)))
            v["customDocumentWidth"] = .number(a["width"].number(793.7)); v["customDocumentHeight"] = .number(a["height"].number(1122.5))
            v["boundlessDocumentWidth"] = .number(a["infiniteWidth"].number(793.7) * androidUnitsPerUnit)
            v["boundlessDocumentHeight"] = .number(a["infiniteHeight"].number(1122.5) * androidUnitsPerUnit)
            v["pageColor"] = .string("WHITE"); p.view = v
        }
        if let dark { var v = p.view; v["darkBackground"] = .bool(dark); p.view = v }
        return p
    }
    public static func makeCanonical(_ p: Page) {
        let v = p.view
        var a = p.json["appearance"] == .null ? JSON.object([:]) : p.json["appearance"]
        a["paperKind"] = .number(p.paper == "PDF" ? 2 : p.paper == "A4" ? 1 : 0)
        a["theme"] = .number(p.dark ? 0 : 1)
        a["background"] = .number(Double(backgroundNames.firstIndex(of: v["backgroundFormat"].string) ?? 5))
        a["showTitle"] = v["pageTitleVisible"]; a["sheetCount"] = .number(Double(p.sheetCount))
        a["width"] = .number(v["customDocumentWidth"].number(793.7)); a["height"] = .number(v["customDocumentHeight"].number(1122.5))
        a["infiniteWidth"] = .number(v["boundlessDocumentWidth"].number(793.7 * androidUnitsPerUnit) / androidUnitsPerUnit)
        a["infiniteHeight"] = .number(v["boundlessDocumentHeight"].number(1122.5 * androidUnitsPerUnit) / androidUnitsPerUnit)
        if a["pageColor"] == .null { a["pageColor"] = .string(p.dark ? "#1B1B1B" : "#FAFAFA") }
        p.json["appearance"] = a
    }
}
