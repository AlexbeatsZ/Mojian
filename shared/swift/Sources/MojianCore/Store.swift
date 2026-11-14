import Foundation

/// Serial archive compatibility adapter backed by the canonical folder store.
public final class WorkspaceStore: @unchecked Sendable {
    public let root: URL
    private let canonical: CanonicalStore
    private let queue = DispatchQueue(label: "com.meta.mojian.store", qos: .utility)
    private var failures = [String: Error]()
    public var onError: (@Sendable (Error) -> Void)?

    public init(root: URL) throws {
        self.root = root.standardizedFileURL
        let fm = FileManager.default
        let exists = fm.fileExists(atPath: root.path)
        let empty = try !exists || fm.contentsOfDirectory(atPath: root.path).isEmpty
        let externalMac = fm.fileExists(atPath: root.appendingPathComponent(".mojian-macos-library").path)
        canonical = try CanonicalStore(root: root, create: empty, writable: !externalMac, owner: "archive")
        _ = try canonical.loadLibrary()
        if !externalMac {
            canonical.discard = { url in
                let recovery = root.appendingPathComponent(".mojian-deletions.previous/\(UUID().uuidString)/\(url.lastPathComponent)")
                try fm.createDirectory(at: recovery.deletingLastPathComponent(), withIntermediateDirectories: true)
                try fm.moveItem(at: url, to: recovery)
            }
        }
    }

    public static func safe(_ value: String) throws -> String {
        // Same component rule as sync-server/protocol.mjs, Windows SafeId and Android safeId.
        guard !value.isEmpty, value.utf8.count < 240, value.first != ".", value.last != ".",
              value.unicodeScalars.allSatisfy({ $0.isASCII && (CharacterSet.alphanumerics.contains($0) || "._-".unicodeScalars.contains($0)) }) else {
            throw MojianError.invalid("无效的文件或内容标识")
        }
        return value
    }

    /// Guards one local path component (including dot-prefixed recovery folders), not a content ID.
    static func safeComponent(_ value: String) throws -> String {
        guard !value.isEmpty, value != ".", value != "..", value.utf8.count < 240,
              value.unicodeScalars.allSatisfy({ CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "._-")).contains($0) }) else {
            throw MojianError.invalid("无效的文件或内容标识")
        }
        return value
    }

    public func pageURL(notebook: String, page: String) throws -> URL {
        try canonical.checked("notebooks/\(Self.safe(notebook))/pages/\(Self.safe(page)).json")
    }
    public func assets(notebook: String, page: String) throws -> URL {
        try canonical.checked("notebooks/\(Self.safe(notebook))/assets/\(Self.safe(page))")
    }
    public func loadLibrary() throws -> [Notebook] { try queue.sync { try canonical.loadLibrary() } }
    public func validate(_ notebooks: [Notebook]) throws {
        var ids = Set<String>()
        for notebook in notebooks {
            for id in [notebook.id] + notebook.allSections.map(\.id) + notebook.pages.map(\.id) {
                _ = try Self.safe(id)
                guard ids.insert(id).inserted else { throw MojianError.invalid("索引中存在重复标识") }
            }
        }
    }

    private func enqueue(_ key: String, action: @escaping @Sendable () throws -> Void) {
        queue.async {
            do { try action(); self.failures.removeValue(forKey: key) }
            catch { self.failures[key] = error; self.onError?(error) }
        }
    }
    public func saveLibrary(_ notebooks: [Notebook], activePage: String? = nil) {
        for page in notebooks.flatMap(\.pages) where page.json["appearance"] == .null { AppearanceBridge.makeCanonical(page) }
        let snapshot = JSON.array(notebooks.map(\.json))
        let navigation = JSON.object([
            "activePageId": .string(activePage ?? ""),
            "expanded": .object(Dictionary(uniqueKeysWithValues: notebooks.flatMap(\.allSections).map { ($0.id, JSON.bool($0.expanded)) }))
        ])
        enqueue("library.json") {
            try self.canonical.saveLibrarySnapshot(snapshot)
            try self.writeLocal(navigation, name: "ios-navigation.json")
        }
    }
    public func lastPageID() -> String? {
        queue.sync {
            if let local = try? readLocal("ios-navigation.json") { return local["activePageId"].string }
            return (try? readLocal("library.json"))?["activePageId"].string
        }
    }
    public func saveViewport(_ viewport: Viewport, page: String) {
        let value = JSON.object(["scale": .number(viewport.scale), "translationX": .number(viewport.x), "translationY": .number(viewport.y)])
        enqueue("ios-viewports.json") {
            var json = (try? self.readLocal("ios-viewports.json")) ?? .object([:])
            json[page] = value
            try self.writeLocal(json, name: "ios-viewports.json")
        }
    }
    public func viewport(page: String) -> JSON? { queue.sync { (try? readLocal("ios-viewports.json"))?[page] } }
    public func writePage(_ document: PageDocument) throws {
        try queue.sync { _ = try canonical.writePage(document, incrementRevision: false) }
    }
    public func loadPage(notebook: String, page: String) throws -> PageDocument {
        try queue.sync { try canonical.loadPage(notebook: notebook, page: page) }
    }
    public func savePage(_ document: PageDocument) {
        enqueue("\(document.notebookID)/\(document.pageID)") { _ = try self.canonical.writePage(document) }
    }
    public func flush() { queue.sync {} }
    public func flushChecked() throws {
        try queue.sync { if let error = failures.values.first { throw error } }
    }
    public func saveSettings(_ settings: Settings) {
        enqueue("ios-settings.json") { try self.writeLocal(JSON.read(JSONEncoder().encode(settings)), name: "ios-settings.json") }
    }
    public func loadSettings() throws -> Settings {
        try queue.sync {
            let url = root.appendingPathComponent("ios-settings.json")
            guard FileManager.default.fileExists(atPath: url.path) else { return Settings() }
            return try JSONDecoder().decode(Settings.self, from: readLocal("ios-settings.json").data())
        }
    }
    public func writeAsset(_ data: Data, notebook: String, page: String, name: String) throws {
        try queue.sync { try canonical.writeAsset(data, notebook: notebook, page: page, name: name) }
    }
    public func deletePage(notebook: String, page: String) throws {
        try queue.sync {
            guard canonical.trash(notebook: notebook, pages: [page]).isEmpty else { throw MojianError.invalid("删除清理失败，页面文件已保留") }
        }
    }
    private func readLocal(_ name: String) throws -> JSON { try JSON.read(Data(contentsOf: canonical.checked(name))) }
    private func writeLocal(_ json: JSON, name: String) throws {
        guard !canonical.readOnly else { throw MojianError.invalid("当前笔记库为只读") }
        try json.data().write(to: canonical.checked(name), options: .atomic)
    }
}
