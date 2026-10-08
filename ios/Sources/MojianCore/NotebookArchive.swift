import Foundation
import ZIPFoundation

public enum NotebookArchive {
    private static let limit: UInt64 = 2 * 1024 * 1024 * 1024
    private static func entryData(_ archive: Archive, path: String) throws -> Data {
        guard let entry=archive[path], entry.type == .file, entry.uncompressedSize <= 256*1024*1024 else { throw MojianError.invalid("笔记包缺少文件或文件过大：\(path)") }
        var data=Data(); _=try archive.extract(entry,consumer:{ data.append($0) }); return data
    }
    public static func export(_ notebook: Notebook, store: WorkspaceStore, to url: URL) throws {
        store.flush()
        let temp=url.deletingLastPathComponent().appendingPathComponent("\(UUID().uuidString).mjn")
        defer { try? FileManager.default.removeItem(at:temp) }
        let archive=try Archive(url:temp,accessMode:.create)
        func add(_ data: Data, _ path: String) throws {
            try archive.addEntry(with:path,type:.file,uncompressedSize:Int64(data.count),compressionMethod:.deflate,provider:{ position,size in
                data.subdata(in:Int(position)..<Int(position)+size)
            })
        }
        let manifest:JSON = .object(["format":.string("mojian-notebook"),"version":.number(1),"notebookId":.string(notebook.id),"title":.string(notebook.title),"sections":.array(notebook.sections.map(\.json)),"pdfLayerOrder":.array([.string("页面背景"),.string("插入内容"),.string("墨迹与文字")])])
        try add(manifest.data(),"manifest.json")
        for p in notebook.pages {
            let d=try store.loadPage(notebook:notebook.id,page:p.id)
            try add(d.json.data(),"windows/pages/\(try WorkspaceStore.safe(p.id)).json")
            var android:JSON = .object(["version":.number(1),"notebookId":.string(notebook.id),"pageId":.string(p.id),"strokes":.array(d.strokes.filter{$0["brushProfile"].string=="shape-v1"})])
            android["objects"] = .array(d.objects.map { item in
                var v=item
                let kind=Int(item["kind"].number(2))
                v["type"] = .string(["TABLE","ATTACHMENT","IMAGE","IMAGE","SPREADSHEET","IMAGE","NOTE_LINK"][min(6,max(0,kind))])
                v["sourceKind"] = .string(kind==3 ? "PDF_PAGE" : kind==5 ? "STICKER" : "IMAGE")
                v["pageBackground"] = item["isPageBackground"]
                if kind==6 { v["targetTitle"] = item["displayName"] }; return v
            })
            try add(android.data(),"pages/\(p.id).json")
            let assets=try store.assets(notebook:notebook.id,page:p.id)
            if FileManager.default.fileExists(atPath:assets.path) {
                for asset in try FileManager.default.contentsOfDirectory(at:assets,includingPropertiesForKeys:[.isRegularFileKey]).sorted(by:{$0.lastPathComponent<$1.lastPathComponent}) {
                    guard try asset.resourceValues(forKeys:[.isRegularFileKey]).isRegularFile == true else { continue }
                    _=try WorkspaceStore.safe(asset.lastPathComponent)
                    try archive.addEntry(with:"assets/\(p.id)/\(asset.lastPathComponent)",fileURL:asset,compressionMethod:.deflate)
                }
            }
        }
        // Destination is app-generated scratch output; external Files copies use the system picker.
        if FileManager.default.fileExists(atPath:url.path) { _=try FileManager.default.replaceItemAt(url,withItemAt:temp) }
        else { try FileManager.default.moveItem(at:temp,to:url) }
    }
    public static func importNotebook(from url: URL, store: WorkspaceStore) throws -> Notebook {
        let archive=try Archive(url:url,accessMode:.read)
        var total:UInt64=0, paths=Set<String>()
        for entry in archive {
            let components=entry.path.split(separator:"/",omittingEmptySubsequences:false)
            guard !entry.path.hasPrefix("/"), !entry.path.contains("\\"), !components.contains(".."), !components.contains("."), entry.type != .symlink,
                  paths.insert(entry.path).inserted else { throw MojianError.invalid("笔记包含无效或重复的路径") }
            total += entry.uncompressedSize
            guard total<=limit, paths.count<=100000 else { throw MojianError.invalid("笔记包超出导入限制") }
        }
        let manifest=try JSON.read(entryData(archive,path:"manifest.json"))
        guard manifest["version"].number()==1, ["mojian-notebook","mojian-section"].contains(manifest["format"].string) else { throw MojianError.invalid("不支持此笔记包版本") }
        let original=Notebook(id:manifest["notebookId"].string,title:manifest["title"].string,sections:manifest["sections"].array.map(Section.init))
        try store.validate([original])
        var mapping=[String:String](); mapping[original.id]=JSON.id("notebook")
        for s in original.allSections { mapping[s.id]=JSON.id("section"); for p in s.pages { mapping[p.id]=JSON.id("page") } }
        let idKeys:Set<String>=["id","notebookId","pageId","sourceOwnerPageId","targetNotebookId","targetPageId","targetSectionId"]
        func remap(_ j:JSON) -> JSON {
            switch j {
            case .array(let a): return .array(a.map(remap))
            case .object(let d): return .object(d.mapValues(remap).reduce(into:[String:JSON]()) { result,entry in
                result[entry.key] = idKeys.contains(entry.key) && mapping[entry.value.string] != nil ? .string(mapping[entry.value.string]!) : entry.value
            })
            default:return j
            }
        }
        let imported=Notebook(remap(original.json)); var staged=[(String,PageDocument)](), assets=[(String,String,Data)]()
        // Validate the entire import before saving any page. A bad late entry cannot become a partial notebook.
        for p in original.pages {
            let old=try WorkspaceStore.safe(p.id), target=mapping[old]!
            let windows="windows/pages/\(old).json", shared="pages/\(old).json"
            var j:JSON
            if archive[windows] != nil { j=try JSON.read(entryData(archive,path:windows));_ = try PageDocument(j) }
            else if archive[shared] != nil {
                j=try JSON.read(entryData(archive,path:shared));_ = try PageDocument(j); j["version"] = .number(2)
                j["objects"] = .array(j["objects"].array.map { item in
                    var v=item; let type=item["type"].string
                    let kind=type=="TABLE" ? 0 : type=="ATTACHMENT" ? 1 : type=="SPREADSHEET" ? 4 : type=="NOTE_LINK" ? 6 : item["sourceKind"].string=="PDF_PAGE" ? 3 : item["sourceKind"].string=="STICKER" ? 5 : 2
                    v["kind"] = .number(Double(kind)); v["isPageBackground"] = item["pageBackground"]
                    if kind==6 { v["displayName"] = item["targetTitle"] }; return v
                })
            } else { j=PageDocument(notebook:original.id,page:old).json }
            j=remap(j); j["notebookId"] = .string(imported.id); j["pageId"] = .string(target)
            staged.append((target,try PageDocument(j)))
            for entry in archive where entry.path.hasPrefix("assets/\(old)/") && entry.type == .file {
                let name=String(entry.path.dropFirst("assets/\(old)/".count)); _=try WorkspaceStore.safe(name)
                assets.append((target,name,try entryData(archive,path:entry.path)))
            }
        }
        do {
            for (p,n,d) in assets { try store.writeAsset(d,notebook:imported.id,page:p,name:n) }
            for (_,d) in staged { try store.writePage(d) }; store.flush()
        } catch {
            for (p,_) in staged { try? store.deletePage(notebook:imported.id,page:p) }; throw error
        }
        return imported
    }
}
