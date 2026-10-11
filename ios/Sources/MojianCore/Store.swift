import Foundation

/// All I/O is serialized, including deletion and close/background flush.
public final class WorkspaceStore: @unchecked Sendable {
    public let root: URL
    private let queue=DispatchQueue(label:"com.meta.mojian.store",qos:.utility)
    public var onError: (@Sendable (Error) -> Void)?
    public init(root: URL) throws {
        self.root=root; try FileManager.default.createDirectory(at:root,withIntermediateDirectories:true)
    }
    public static func safe(_ value: String) throws -> String {
        guard !value.isEmpty, value != ".", value != "..", value.utf8.count<240,
              value.unicodeScalars.allSatisfy({ CharacterSet(charactersIn:"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-").contains($0) }) else {
            throw MojianError.invalid("无效的文件或内容标识")
        }; return value
    }
    public func pageURL(notebook: String, page: String) throws -> URL { root.appendingPathComponent("notebooks/\(try Self.safe(notebook))/pages/\(try Self.safe(page)).json") }
    public func assets(notebook: String, page: String) throws -> URL { root.appendingPathComponent("notebooks/\(try Self.safe(notebook))/assets/\(try Self.safe(page))") }
    private func read(_ url: URL) throws -> JSON {
        let fm=FileManager.default, prior=url.appendingPathExtension("previous")
        if !fm.fileExists(atPath:url.path) {
            if fm.fileExists(atPath:prior.path) { return try JSON.read(Data(contentsOf:prior)) }
            throw MojianError.invalid("找不到文件：\(url.lastPathComponent)")
        }
        do { return try JSON.read(Data(contentsOf:url)) }
        catch { if fm.fileExists(atPath:prior.path), let restored=try? JSON.read(Data(contentsOf:prior)) { return restored }; throw error }
    }
    private func write(_ json: JSON, to url: URL) throws {
        let fm=FileManager.default; try fm.createDirectory(at:url.deletingLastPathComponent(),withIntermediateDirectories:true)
        // Only rotate a valid current file. A corrupt current file must not destroy a valid prior revision.
        if let current=try? Data(contentsOf:url),let parsed=try? JSON.read(current),url.deletingLastPathComponent().lastPathComponent != "pages" || (try? PageDocument(parsed)) != nil { try current.write(to:url.appendingPathExtension("previous"),options:.atomic) }
        try json.data().write(to:url,options:.atomic)
    }
    public func loadLibrary() throws -> [Notebook] {
        try queue.sync {
            let url=root.appendingPathComponent("library.json")
            if !FileManager.default.fileExists(atPath:url.path), !FileManager.default.fileExists(atPath:url.appendingPathExtension("previous").path) {
                let n=Notebook(sections:[Section(title:"笔记",pages:[Page(title:"开始书写")])]); return [n]
            }
            let j=try read(url); guard j["version"].number(1)==1, !j["notebooks"].array.isEmpty else { throw MojianError.invalid("笔记本索引无效") }
            let n=j["notebooks"].array.map(Notebook.init); try validate(n); return n
        }
    }
    public func validate(_ notebooks: [Notebook]) throws {
        var ids=Set<String>()
        for n in notebooks {
            _=try Self.safe(n.id); guard ids.insert(n.id).inserted else { throw MojianError.invalid("重复的笔记本标识") }
            var local=Set<String>()
            for s in n.allSections {
                _=try Self.safe(s.id); guard local.insert(s.id).inserted else { throw MojianError.invalid("重复的分区标识") }
                for p in s.pages { _=try Self.safe(p.id); guard local.insert(p.id).inserted else { throw MojianError.invalid("重复的页面标识") } }
            }
        }
    }
    public func saveLibrary(_ notebooks: [Notebook], activePage: String? = nil) {
        let j:JSON = .object(["version":.number(1),"notebooks":.array(notebooks.map(\.json)),"activePageId":.string(activePage ?? "")])
        queue.async { do { try self.write(j,to:self.root.appendingPathComponent("library.json")) } catch { self.onError?(error) } }
    }
    public func lastPageID() -> String? {
        queue.sync { (try? read(root.appendingPathComponent("library.json")))?["activePageId"].string }
    }
    public func saveViewport(_ viewport: Viewport, page: String) {
        let value:JSON = .object(["scale":.number(viewport.scale),"translationX":.number(viewport.x),"translationY":.number(viewport.y)])
        queue.async {
            do {
                let url=self.root.appendingPathComponent("ios-viewports.json")
                var j=(try? self.read(url)) ?? .object([:]);j[page]=value;try self.write(j,to:url)
            }catch{self.onError?(error)}
        }
    }
    public func viewport(page:String) -> JSON? { queue.sync { (try? read(root.appendingPathComponent("ios-viewports.json")))?[page] } }
    public func writePage(_ document:PageDocument) throws {
        try queue.sync {try write(document.json,to:pageURL(notebook:document.notebookID,page:document.pageID))}
    }
    public func loadPage(notebook: String, page: String) throws -> PageDocument {
        try queue.sync {
            let url=try pageURL(notebook:notebook,page:page)
            if !FileManager.default.fileExists(atPath:url.path), !FileManager.default.fileExists(atPath:url.appendingPathExtension("previous").path) { return PageDocument(notebook:notebook,page:page) }
            func decode(_ json:JSON)throws->PageDocument {
                var document=try PageDocument(json)
                guard document.pageID==page else {throw MojianError.invalid("页面标识与文件不一致")}
                document.json["notebookId"] = .string(notebook);return document
            }
            let j=try read(url)
            guard j["version"].number(1)<=2 else {throw MojianError.invalid("页面版本不受支持")}
            do {return try decode(j)}
            catch {
                if let prior=try? JSON.read(Data(contentsOf:url.appendingPathExtension("previous"))),let restored=try? decode(prior){return restored}
                throw error
            }
        }
    }
    public func savePage(_ document: PageDocument) {
        var j=document.json; j["revision"] = .number(j["revision"].number()+1); j["updatedAt"] = .string(ISO8601DateFormatter().string(from:Date()))
        queue.async { do { try self.write(j,to:self.pageURL(notebook:document.notebookID,page:document.pageID)) } catch { self.onError?(error) } }
    }
    public func flush() { queue.sync {} }
    public func saveSettings(_ settings: Settings) {
        queue.async { do { try self.write(JSON.read(JSONEncoder().encode(settings)),to:self.root.appendingPathComponent("ios-settings.json")) } catch { self.onError?(error) } }
    }
    public func loadSettings() throws -> Settings {
        try queue.sync {
            let url=root.appendingPathComponent("ios-settings.json")
            guard FileManager.default.fileExists(atPath:url.path) else { return Settings() }
            return try JSONDecoder().decode(Settings.self,from:read(url).data())
        }
    }
    public func writeAsset(_ data: Data, notebook: String, page: String, name: String) throws {
        try queue.sync {
            let dir=try assets(notebook:notebook,page:page); try FileManager.default.createDirectory(at:dir,withIntermediateDirectories:true)
            try data.write(to:dir.appendingPathComponent(Self.safe(name)),options:.atomic)
        }
    }
    public func deletePage(notebook: String, page: String) throws {
        try queue.sync {
            let fm=FileManager.default
            for u in [try pageURL(notebook:notebook,page:page),try pageURL(notebook:notebook,page:page).appendingPathExtension("previous"),try assets(notebook:notebook,page:page)] where fm.fileExists(atPath:u.path) { try fm.removeItem(at:u) }
        }
    }
}
