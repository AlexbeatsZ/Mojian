import Foundation
import CryptoKit
import Darwin

/// Names of the v3 folder library (docs/design/folder-library.md); the same rules as tools/folder-library/convert.mjs,
/// Windows FolderNames and Android FolderNames, so one title maps to one file name on every device.
public enum FolderNames {
    public static let pageExtension = ".mojian"
    public static let descriptor = ".mojian-folder.json"
    public static let hiddenRoot = ".mojian"
    private static let fullWidth: [Unicode.Scalar: String] = ["\\": "＼", "/": "／", ":": "：", "*": "＊", "?": "？",
                                                             "\"": "＂", "<": "＜", ">": "＞", "|": "｜"]

    public static func storable(_ title: String?) -> String {
        var text = ""
        for scalar in (title ?? "").unicodeScalars {
            if scalar.value < 0x20 || scalar.value == 0x7f { continue }
            if let mapped = fullWidth[scalar] { text += mapped } else { text.unicodeScalars.append(scalar) }
        }
        var name = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if name.hasPrefix(".") { name = "．" + name.dropFirst() }
        if name.hasSuffix(".") { name = name.dropLast() + "．" }
        if name.isEmpty { name = "无标题" }
        if name.range(of: "^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?$", options: [.regularExpression, .caseInsensitive]) != nil { name += "_" }
        let scalars = Array(name.unicodeScalars)
        if scalars.count > 120 {
            var cut = String.UnicodeScalarView(); cut.append(contentsOf: scalars[0..<120])
            return String(cut).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return name
    }

    /// Explorer-style de-duplication, case-insensitive across the pages and folders of one parent.
    public static func unique(_ base: String, suffix: String, taken: inout Set<String>) -> String {
        var n = 1
        while true {
            let candidate = (n == 1 ? base : "\(base) (\(n))") + suffix
            if taken.insert(candidate.lowercased()).inserted { return candidate }
            n += 1
        }
    }

    /// True when `name` already stores `title`, possibly with a de-duplication suffix.
    public static func represents(_ name: String, title: String?, suffix: String = "") -> Bool {
        guard name.hasSuffix(suffix) else { return false }
        let stem = String(name.dropLast(suffix.count)), wanted = storable(title)
        if stem == wanted { return true }
        guard let match = stem.range(of: " \\((\\d+)\\)$", options: .regularExpression) else { return false }
        let number = Int(stem[match].dropFirst(2).dropLast()) ?? 0
        return String(stem[..<match.lowerBound]) == wanted && number >= 2
    }
}

/// Fractional ordering keys, a port of rocicorp/fractional-indexing (CC0-1.0); compared ordinally.
public enum FractionalIndex {
    private static let digits = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")
    private static let zero: Character = "0"
    private static func index(_ c: Character) -> Int { digits.firstIndex(of: c) ?? -1 }

    private static func midpoint(_ a: [Character], _ b: [Character]?) -> [Character] {
        if let b, !b.isEmpty {
            var n = 0
            while (n < a.count ? a[n] : zero) == b[n] { n += 1 }
            if n > 0 { return Array(b[0..<n]) + midpoint(a.count > n ? Array(a[n...]) : [], Array(b[n...])) }
        }
        let digitA = a.isEmpty ? 0 : index(a[0])
        let digitB = b.map { index($0[0]) } ?? digits.count
        if digitB - digitA > 1 { return [digits[Int((0.5 * Double(digitA + digitB)).rounded())]] }
        if let b, b.count > 1 { return [b[0]] }
        return [digits[digitA]] + midpoint(a.count > 1 ? Array(a[1...]) : [], nil)
    }
    private static func integerLength(_ head: Character) -> Int? {
        guard let v = head.asciiValue else { return nil }
        if v >= 97 && v <= 122 { return Int(v) - 97 + 2 }
        if v >= 65 && v <= 90 { return 90 - Int(v) + 2 }
        return nil
    }
    private static func integerPart(_ key: [Character]) -> [Character]? {
        guard let first = key.first, let length = integerLength(first), length <= key.count else { return nil }
        return Array(key[0..<length])
    }
    private static let smallest = Array("A" + String(repeating: "0", count: 26))

    public static func isValid(_ key: String?) -> Bool {
        guard let key, !key.isEmpty else { return false }
        let chars = Array(key)
        guard chars != smallest, chars.allSatisfy({ index($0) >= 0 }), let integer = integerPart(chars) else { return false }
        return chars.count == integer.count || chars.last != zero
    }
    private static func increment(_ x: [Character]) -> [Character]? {
        let head = x[0]; var d = Array(x[1...]); var carry = true; var i = d.count - 1
        while carry && i >= 0 {
            let next = index(d[i]) + 1
            if next == digits.count { d[i] = zero } else { d[i] = digits[next]; carry = false }
            i -= 1
        }
        if !carry { return [head] + d }
        if head == "Z" { return ["a", zero] }
        if head == "z" { return nil }
        let next = Character(Unicode.Scalar(head.asciiValue! + 1))
        if next > "a" { d.append(zero) } else { d.removeLast() }
        return [next] + d
    }
    private static func decrement(_ x: [Character]) -> [Character]? {
        let head = x[0]; var d = Array(x[1...]); var borrow = true; var i = d.count - 1
        while borrow && i >= 0 {
            let next = index(d[i]) - 1
            if next == -1 { d[i] = digits.last! } else { d[i] = digits[next]; borrow = false }
            i -= 1
        }
        if !borrow { return [head] + d }
        if head == "a" { return ["Z", digits.last!] }
        if head == "A" { return nil }
        let next = Character(Unicode.Scalar(head.asciiValue! - 1))
        if next < "Z" { d.append(digits.last!) } else { d.removeLast() }
        return [next] + d
    }

    /// A key strictly between `a` and `b`; nil is unbounded.
    public static func between(_ a: String?, _ b: String?) -> String {
        precondition(a == nil || isValid(a), "invalid order key")
        precondition(b == nil || isValid(b), "invalid order key")
        precondition(a == nil || b == nil || a! < b!, "order keys out of order")
        guard let a else {
            guard let b else { return "a0" }
            let kb = Array(b), ib = integerPart(kb)!
            if ib == smallest { return String(ib + midpoint([], Array(kb[ib.count...]))) }
            if ib.count < kb.count { return String(ib) }
            return String(decrement(ib)!)
        }
        let ka = Array(a), ia = integerPart(ka)!
        guard let b else {
            if let next = increment(ia) { return String(next) }
            return String(ia + midpoint(Array(ka[ia.count...]), nil))
        }
        let kb = Array(b), ib = integerPart(kb)!
        if ia == ib { return String(ia + midpoint(Array(ka[ia.count...]), Array(kb[ib.count...]))) }
        let next = increment(ia)!
        return String(next) < b ? String(next) : String(ia + midpoint(Array(ka[ia.count...]), nil))
    }

    /// Keys for `ordered`, keeping every still-increasing existing key so a move rewrites only the moved items.
    public static func assign(_ ordered: [String], existing: [String: String]) -> [String: String] {
        var result = [String: String](), previous: String?
        for (i, id) in ordered.enumerated() {
            if let key = existing[id], isValid(key), previous == nil || key > previous! { previous = key; result[id] = key; continue }
            var next: String?
            for later in ordered[(i + 1)...] {
                if let key = existing[later], isValid(key), previous == nil || key > previous! { next = key; break }
            }
            let assigned = between(previous, next)
            previous = assigned; result[id] = assigned
        }
        return result
    }
}

/// `{"version":3,"id","sortKey","page":{…},"content":{…}}`; writers always put `page` before `content`, so listing a
/// library reads only the bytes before `content`.
public enum PageFileFormat {
    public struct Head: Equatable { public var version: Int; public var id: String?; public var sortKey: String?; public var page: JSON }

    /// Offset of the top-level `"content"` key, or nil.
    static func contentOffset(_ bytes: UnsafeRawBufferPointer) -> Int? {
        var depth = 0, i = 0, expectKey = false
        let quote = UInt8(ascii: "\""), backslash = UInt8(ascii: "\\")
        let key = Array("content".utf8)
        while i < bytes.count {
            let c = bytes[i]
            if c == quote {
                let start = i; i += 1
                var escaped = false
                while i < bytes.count {
                    let s = bytes[i]
                    if escaped { escaped = false } else if s == backslash { escaped = true } else if s == quote { break }
                    i += 1
                }
                if depth == 1 && expectKey {
                    if i - start - 1 == key.count && zip(key, bytes[(start + 1)..<i]).allSatisfy({ $0 == $1 }) { return start }
                    expectKey = false
                }
            } else if c == UInt8(ascii: "{") || c == UInt8(ascii: "[") {
                depth += 1; if depth == 1 { expectKey = true }
            } else if c == UInt8(ascii: "}") || c == UInt8(ascii: "]") {
                depth -= 1
            } else if c == UInt8(ascii: ",") && depth == 1 { expectKey = true }
            i += 1
        }
        return nil
    }

    /// Bytes before the `"content"` key (ending in its comma), or nil when the file has no content after a head.
    public static func headPrefix(_ data: Data) -> Data? {
        guard let offset = data.withUnsafeBytes(contentOffset), offset > 1 else { return nil }
        return data.prefix(offset)
    }

    public static func readHead(_ url: URL) throws -> Head {
        let data = try Data(contentsOf: url, options: .alwaysMapped)
        if var prefix = headPrefix(data) {
            while let last = prefix.last, last == UInt8(ascii: ",") || last == 0x20 || last == 0x0a || last == 0x0d || last == 0x09 { prefix.removeLast() }
            prefix.append(UInt8(ascii: "}"))
            if let head = try? JSON.read(prefix) { return Self.head(head) }
        }
        return head(try JSON.read(data))
    }
    private static func head(_ json: JSON) -> Head {
        guard case .object = json else { return Head(version: 0, id: nil, sortKey: nil, page: .null) }
        return Head(version: Int(json["version"].number()), id: json["id"].string.isEmpty ? nil : json["id"].string,
                    sortKey: json["sortKey"].string.isEmpty ? nil : json["sortKey"].string, page: json["page"])
    }

    public static func compose(id: String, sortKey: String, page: JSON, content: Data) throws -> Data {
        var data = Data("{\"version\":3,\"id\":".utf8)
        data += try JSON.string(id).data(); data += Data(",\"sortKey\":".utf8); data += try JSON.string(sortKey).data()
        data += Data(",\"page\":".utf8); data += try (page == .null ? JSON.object([:]) : page).data()
        data += Data(",\"content\":".utf8); data += content; data += Data("}".utf8)
        return data
    }
    /// New content under the head bytes as another device wrote them.
    public static func recompose(prefix: Data, content: Data) -> Data {
        var data = prefix; data += Data("\"content\":".utf8); data += content; data += Data("}".utf8)
        return data
    }
}

/// The v3 folder library as the v2-shaped library JSON (`{"version":2,"notebooks":[…]}`) the models understand.
/// Reading walks the folder tree, adopting folders and pages made in a file manager; applying turns a changed library
/// into folder operations. A read-only library is never written: missing identities are derived from paths instead.
public final class FolderLibrary: @unchecked Sendable {
    public struct Index {
        public var folders = [String: String]()     // notebook/section id -> relative folder
        public var pages = [String: String]()       // page id -> relative file
        public var keys = [String: String]()        // item id -> sortKey
        var pageMeta = [String: JSON]()             // page id -> stored "page" object
        var folderMeta = [String: JSON]()           // folder id -> descriptor extras
        public var pageOwner = [String: String]()   // page id -> notebook id
        var pageObjects = [String: JSON]()          // relative page file -> page node
        var duplicates = [(String, String)]()
    }
    public let root: URL
    public let readOnly: Bool
    public private(set) var index = Index()
    private var indexed = false
    private let fm = FileManager.default
    /// Removal of a page file or folder the library no longer lists; macOS moves it to the Trash.
    public var discard: (URL) throws -> Void = { try FileManager.default.removeItem(at: $0) }
    public private(set) var recovered = [String]()

    public init(root: URL, readOnly: Bool) { self.root = root; self.readOnly = readOnly }

    public func url(_ relative: String) -> URL {
        relative.split(separator: "/").reduce(root) { $0.appendingPathComponent(String($1)) }
    }
    private func combine(_ parent: String, _ name: String) -> String { parent.isEmpty ? name : parent + "/" + name }
    public func relative(_ url: URL) -> String {
        let base = root.standardizedFileURL.path, path = url.standardizedFileURL.path
        return path.hasPrefix(base + "/") ? String(path.dropFirst(base.count + 1)) : path
    }
    public func pendingURL(_ pageId: String) throws -> URL { url("\(FolderNames.hiddenRoot)/pending/\(try WorkspaceStore.safe(pageId)).json") }
    public func assetDirectory(_ pageId: String) throws -> URL { url("\(FolderNames.hiddenRoot)/assets/\(try WorkspaceStore.safe(pageId))") }
    public func pageURL(_ pageId: String) throws -> URL? { try ensureIndex(); return index.pages[pageId].map(url) }
    public func ensureIndex() throws { if !indexed { _ = try read() } }

    /// Where the previous version of a library file is kept: under `.mojian/previous`, never beside a page.
    public func previousURL(_ file: URL) -> URL {
        let r = relative(file)
        return r.hasPrefix(FolderNames.hiddenRoot + "/") || r.hasPrefix("/") ? URL(fileURLWithPath: file.path + ".previous")
            : url("\(FolderNames.hiddenRoot)/previous/\(r).previous")
    }

    /// True when `root` holds a folder library.
    public static func isLibrary(_ root: URL) -> Bool {
        let fm = FileManager.default
        var directory: ObjCBool = false
        if fm.fileExists(atPath: root.appendingPathComponent(FolderNames.hiddenRoot).path, isDirectory: &directory), directory.boolValue { return true }
        return ((try? fm.contentsOfDirectory(atPath: root.path)) ?? []).contains { name in
            !name.hasPrefix(".") && fm.fileExists(atPath: root.appendingPathComponent(name).appendingPathComponent(FolderNames.descriptor).path)
        }
    }

    // MARK: Reading

    private struct FolderRead { var id: String; var name: String; var extras: JSON; var folders: [FolderRead]; var pages: [String] }

    public func read() throws -> JSON {
        var next = Index()
        recovered = []
        let notebooks = try readFolders("", &next, notebook: nil)
        try resolveDuplicates(&next)
        index = next; indexed = true
        return .object(["version": .number(2), "notebooks": .array(notebooks.map(notebookJSON))])
    }
    private func merged(_ extras: JSON, _ values: [String: JSON]) -> JSON {
        var result = extras.dictionary; for (k, v) in values { result[k] = v }; return .object(result)
    }
    private func notebookJSON(_ folder: FolderRead) -> JSON {
        merged(folder.extras, ["id": .string(folder.id), "title": .string(folder.name), "sections": .array(folder.folders.map(sectionJSON))])
    }
    private func sectionJSON(_ folder: FolderRead) -> JSON {
        merged(folder.extras, ["id": .string(folder.id), "title": .string(folder.name), "children": .array(folder.folders.map(sectionJSON)),
                               "pages": .array(folder.pages.compactMap { index.pageObjects[$0] })])
    }
    private func isDirectory(_ url: URL) -> Bool {
        guard let a = try? fm.attributesOfItem(atPath: url.path) else { return false }
        return a[.type] as? FileAttributeType == .typeDirectory
    }
    private func isRegular(_ url: URL) -> Bool {
        guard let a = try? fm.attributesOfItem(atPath: url.path) else { return false }
        return a[.type] as? FileAttributeType == .typeRegular
    }
    /// A stable identity for an item without one: random when this device may record it, else derived from its path.
    private func newId(_ prefix: String, relative: String) -> String {
        if !readOnly { return "\(prefix)-\(UUID().uuidString.lowercased().replacingOccurrences(of: "-", with: ""))" }
        let digest = SHA256.hash(data: Data(relative.utf8)).map { String(format: "%02x", $0) }.joined()
        return "\(prefix)-\(digest.prefix(32))"
    }
    private static func safe(_ id: String) -> Bool { (try? WorkspaceStore.safe(id)) != nil }

    private func readDescriptor(_ file: URL) throws -> JSON {
        guard isRegular(file) else { return .object([:]) }
        if let json = try? JSON.read(Data(contentsOf: file)), case .object = json { return json }
        let previous = previousURL(file)
        recovered.append(relative(file))
        if !readOnly {
            let corrupt = url(".mojian-corrupt.previous/\(relative(file)).\(UUID().uuidString)")
            try fm.createDirectory(at: corrupt.deletingLastPathComponent(), withIntermediateDirectories: true)
            try fm.copyItem(at: file, to: corrupt)
        }
        if let json = try? JSON.read(Data(contentsOf: previous)), case .object = json {
            if !readOnly { try atomic(Data(contentsOf: previous), to: file) }
            return json
        }
        return .object([:])
    }

    private func readFolders(_ relative: String, _ index: inout Index, notebook: String?) throws -> [FolderRead] {
        let directory = relative.isEmpty ? root : url(relative)
        var result = [FolderRead]()
        for name in try fm.contentsOfDirectory(atPath: directory.path).sorted() where !name.hasPrefix(".") {
            let folder = directory.appendingPathComponent(name)
            guard isDirectory(folder) else { continue }
            let childRelative = combine(relative, name)
            let descriptorFile = folder.appendingPathComponent(FolderNames.descriptor)
            var descriptor = try readDescriptor(descriptorFile)
            var id = descriptor["id"].string
            if id.isEmpty || index.folders[id] != nil || !Self.safe(id) {
                // A folder made in a file manager, or a copied descriptor, becomes its own notebook/section.
                id = newId(notebook == nil ? "notebook" : "section", relative: childRelative)
                descriptor["id"] = .string(id); descriptor["version"] = .number(3)
                if !readOnly { try atomic(descriptor.data(), to: descriptorFile) }
            }
            index.folders[id] = childRelative
            var extras = descriptor.dictionary
            for key in ["version", "id", "sortKey"] { extras.removeValue(forKey: key) }
            index.folderMeta[id] = .object(extras)
            let key = descriptor["sortKey"].string
            index.keys[id] = FractionalIndex.isValid(key) ? key : ""
            let owner = notebook ?? id
            let children = try readFolders(childRelative, &index, notebook: owner)
            let pages = notebook == nil ? [] : try readPages(childRelative, &index, notebook: owner)
            result.append(FolderRead(id: id, name: name, extras: .object(extras), folders: children, pages: pages))
        }
        assignMissingKeys(result.map { ($0.id, $0.name) }, &index)
        return result.sorted { (index.keys[$0.id]!, $0.name) < (index.keys[$1.id]!, $1.name) }
    }

    private func readPages(_ relative: String, _ index: inout Index, notebook: String) throws -> [String] {
        var pages = [(String, String)]()   // relative file, title
        for name in try fm.contentsOfDirectory(atPath: url(relative).path).sorted()
        where name.hasSuffix(FolderNames.pageExtension) && !name.hasPrefix(".") {
            let file = url(relative).appendingPathComponent(name)
            // A corrupt page keeps its place through the previous version's head; loading it recovers the content.
            guard isRegular(file), let head = (try? PageFileFormat.readHead(file)) ?? (try? PageFileFormat.readHead(previousURL(file))) else { continue }
            let relativeFile = combine(relative, name)
            var id = head.id ?? ""
            if !id.isEmpty && Self.safe(id) && index.pages[id] != nil {
                index.duplicates.append((relativeFile, id))
                id = newId("page", relative: relativeFile)
            } else if id.isEmpty || !Self.safe(id) {
                id = newId("page", relative: relativeFile)
                if !readOnly { try rewriteHead(file, id: id, sortKey: head.sortKey ?? "", page: head.page) }
            }
            let meta: JSON = head.page == .null ? .object([:]) : head.page
            let title = String(name.dropLast(FolderNames.pageExtension.count))
            index.pages[id] = relativeFile; index.pageOwner[id] = notebook; index.pageMeta[id] = meta
            index.keys[id] = FractionalIndex.isValid(head.sortKey) ? head.sortKey! : ""
            index.pageObjects[relativeFile] = merged(meta, ["id": .string(id), "title": .string(title)])
            pages.append((relativeFile, title))
        }
        let ids = pages.map { index.pageObjects[$0.0]!["id"].string }
        assignMissingKeys(zip(ids, pages.map(\.1)).map { ($0, $1) }, &index)
        return pages.sorted { a, b in
            let ka = index.keys[index.pageObjects[a.0]!["id"].string]!, kb = index.keys[index.pageObjects[b.0]!["id"].string]!
            return (ka, a.1) < (kb, b.1)
        }.map(\.0)
    }

    private func assignMissingKeys(_ items: [(String, String)], _ index: inout Index) {
        var last = items.compactMap { index.keys[$0.0] }.filter { !$0.isEmpty }.max()
        for (id, _) in items.sorted(by: { $0.1 < $1.1 }) where (index.keys[id] ?? "").isEmpty {
            let key = FractionalIndex.between(last, nil); index.keys[id] = key; last = key
        }
    }

    /// The file the previous index knew keeps a duplicated id (otherwise the older file); the copy gets a new id.
    private func resolveDuplicates(_ next: inout Index) throws {
        for (copyPath, originalId) in next.duplicates {
            let firstPath = next.pages[originalId]!
            let copyId = next.pageObjects[copyPath]!["id"].string
            let copyWins = index.pages[originalId].map { $0 == copyPath } ?? (created(url(copyPath)) < created(url(firstPath)))
            let loserPath = copyWins ? firstPath : copyPath
            if copyWins {
                next.pageObjects[firstPath]!["id"] = .string(copyId); next.pageObjects[copyPath]!["id"] = .string(originalId)
                next.pages[originalId] = copyPath; next.pages[copyId] = firstPath
                swap(&next.pageMeta, originalId, copyId); swap(&next.keys, originalId, copyId); swap(&next.pageOwner, originalId, copyId)
            }
            if !readOnly {
                let loser = url(loserPath), head = try PageFileFormat.readHead(loser)
                try rewriteHead(loser, id: copyId, sortKey: head.sortKey ?? "", page: head.page)
            }
        }
        next.duplicates.removeAll()
    }
    private func swap<V>(_ map: inout [String: V], _ a: String, _ b: String) {
        let first = map.removeValue(forKey: a), second = map.removeValue(forKey: b)
        if let second { map[a] = second }
        if let first { map[b] = first }
    }
    private func created(_ url: URL) -> Date {
        let a = try? fm.attributesOfItem(atPath: url.path)
        return (a?[.creationDate] as? Date) ?? (a?[.modificationDate] as? Date) ?? .distantPast
    }

    /// Folder names, page files and descriptor stamps; page saves change only content and never look structural.
    public func signature() -> String {
        var hasher = SHA256()
        func walk(_ directory: URL, _ relative: String) {
            for name in ((try? fm.contentsOfDirectory(atPath: directory.path)) ?? []).sorted() {
                if name.hasPrefix(".") && name != FolderNames.descriptor { continue }
                let entry = directory.appendingPathComponent(name), path = combine(relative, name)
                if isDirectory(entry) { hasher.update(data: Data("D \(path)\n".utf8)); walk(entry, path) }
                else if name == FolderNames.descriptor {
                    let a = try? fm.attributesOfItem(atPath: entry.path)
                    let size = (a?[.size] as? NSNumber)?.int64Value ?? 0, modified = (a?[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
                    hasher.update(data: Data("F \(path) \(size):\(modified)\n".utf8))
                } else if name.hasSuffix(FolderNames.pageExtension) { hasher.update(data: Data("P \(path)\n".utf8)) }
            }
        }
        walk(root, "")
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    // MARK: Applying a changed library

    private struct Placement { var id: String; var title: String; var isPage: Bool; var parent: String?; var owner: String; var json: JSON }
    private static let navigation = ["expanded", "focusedSectionId", "selectedPageSectionId", "selectedPageId", "pagePaneMode", "activeNotebookId"]

    /// Turns `library` into folder and file operations. `beforeWrite` sees every existing page file about to change;
    /// `afterWrite(pageId, file)` every page file written or moved.
    public func apply(_ library: JSON, beforeWrite: (String, URL) throws -> Void = { _, _ in }, afterWrite: (String, URL) throws -> Void = { _, _ in }) throws {
        guard !readOnly else { throw MojianError.invalid("当前笔记库为只读") }
        try ensureIndex()
        var placements = [Placement]()
        func addSection(_ section: JSON, parent: String, owner: String) {
            let id = section["id"].string
            placements.append(Placement(id: id, title: section["title"].string, isPage: false, parent: parent, owner: owner, json: section))
            for page in section["pages"].array {
                placements.append(Placement(id: page["id"].string, title: page["title"].string, isPage: true, parent: id, owner: owner, json: page))
            }
            for child in section["children"].array { addSection(child, parent: id, owner: owner) }
        }
        for notebook in library["notebooks"].array {
            let id = notebook["id"].string
            placements.append(Placement(id: id, title: notebook["title"].string, isPage: false, parent: nil, owner: id, json: notebook))
            for section in notebook["sections"].array { addSection(section, parent: id, owner: id) }
        }
        let wanted = Set(placements.map(\.id))
        guard wanted.count == placements.count, placements.allSatisfy({ Self.safe($0.id) }) else { throw MojianError.invalid("笔记库中存在重复或无效标识") }

        for (id, relative) in index.pages where !wanted.contains(id) {
            let file = url(relative)
            if fm.fileExists(atPath: file.path) { try beforeWrite(id, file); try discard(file) }
            let assets = try assetDirectory(id)
            if fm.fileExists(atPath: assets.path) { try discard(assets) }
            index.pages.removeValue(forKey: id); index.pageMeta.removeValue(forKey: id); index.keys.removeValue(forKey: id); index.pageOwner.removeValue(forKey: id)
        }
        for (id, relative) in index.folders.filter({ !wanted.contains($0.key) }).sorted(by: { $0.value.count > $1.value.count }) {
            let folder = url(relative)
            if fm.fileExists(atPath: folder.path) { try discard(folder) }
            index.folders.removeValue(forKey: id); index.folderMeta.removeValue(forKey: id); index.keys.removeValue(forKey: id)
        }

        var keys = [String: String]()
        var groups = [String: [String]](), order = [String]()
        for item in placements {
            let group = "\(item.parent ?? "")|\(item.isPage)"
            if groups[group] == nil { order.append(group) }
            groups[group, default: []].append(item.id)
        }
        for group in order { keys.merge(FractionalIndex.assign(groups[group]!, existing: index.keys)) { $1 } }

        func current(_ item: Placement) -> String? { item.isPage ? index.pages[item.id] : index.folders[item.id] }
        func parentOf(_ path: String) -> String { path.contains("/") ? String(path[..<path.lastIndex(of: "/")!]) : "" }
        func nameOf(_ path: String) -> String { path.contains("/") ? String(path[path.index(after: path.lastIndex(of: "/")!)...]) : path }
        var names = [String: String]()
        var byParent = [String: [Placement]](), parents = [String]()
        for item in placements {
            let key = item.parent ?? ""
            if byParent[key] == nil { parents.append(key) }
            byParent[key, default: []].append(item)
        }
        for parent in parents {
            let parentRelative = parent.isEmpty ? "" : index.folders[parent]
            var taken: Set<String> = [FolderNames.descriptor.lowercased()]
            var decided = [(Placement, String?)]()
            for item in byParent[parent]! {
                let suffix = item.isPage ? FolderNames.pageExtension : ""
                var keep: String?
                if let path = current(item), parentOf(path) == parentRelative, FolderNames.represents(nameOf(path), title: item.title, suffix: suffix),
                   taken.insert(nameOf(path).lowercased()).inserted { keep = nameOf(path) }
                decided.append((item, keep))
            }
            for (item, name) in decided {
                names[item.id] = name ?? FolderNames.unique(FolderNames.storable(item.title), suffix: item.isPage ? FolderNames.pageExtension : "", taken: &taken)
            }
        }
        let byId = Dictionary(uniqueKeysWithValues: placements.map { ($0.id, $0) })
        func final(_ item: Placement) -> String {
            guard let parent = item.parent else { return names[item.id]! }
            return combine(final(byId[parent]!), names[item.id]!)
        }

        // Two-phase moves through hidden temporary names: swaps and moves into renamed parents never collide.
        var staged = Set<String>()
        let moving = placements.filter { item in current(item).map { $0 != final(item) } ?? false }
        for item in moving.sorted(by: { current($0)!.count > current($1)!.count }) {
            let path = current(item)!, source = url(path)
            let temporary = source.deletingLastPathComponent().appendingPathComponent(".mojian-move-\(UUID().uuidString)")
            if item.isPage { try beforeWrite(item.id, source) }
            try move(source, to: temporary)
            moveIndex(path, to: relative(temporary), page: item.isPage)
            staged.insert(item.id)
        }
        for item in placements {
            let path = final(item), target = url(path)
            if !item.isPage {
                if staged.contains(item.id) { try move(url(index.folders[item.id]!), to: target) }
                else if !isDirectory(target) { try fm.createDirectory(at: target, withIntermediateDirectories: true) }
                moveIndex(index.folders[item.id] ?? path, to: path, page: false)
                index.folders[item.id] = path
                var extras = item.json.dictionary
                for key in ["id", "title", "sections", "children", "pages"] + Self.navigation { extras.removeValue(forKey: key) }
                let descriptor = target.appendingPathComponent(FolderNames.descriptor)
                if !isRegular(descriptor) || index.keys[item.id] != keys[item.id] || index.folderMeta[item.id] != .object(extras) {
                    var json = extras; json["version"] = .number(3); json["id"] = .string(item.id); json["sortKey"] = .string(keys[item.id]!)
                    try atomic(JSON.object(json).data(), to: descriptor)
                }
                index.folderMeta[item.id] = .object(extras)
            } else {
                if staged.contains(item.id) {
                    try move(url(index.pages[item.id]!), to: target)
                    index.pages[item.id] = path
                    try afterWrite(item.id, target)
                }
                try writePageHead(item, target: target, key: keys[item.id]!, beforeWrite: beforeWrite, afterWrite: afterWrite)
            }
            index.keys[item.id] = keys[item.id]!
        }
    }

    private func writePageHead(_ item: Placement, target: URL, key: String, beforeWrite: (String, URL) throws -> Void, afterWrite: (String, URL) throws -> Void) throws {
        var meta = item.json.dictionary; meta.removeValue(forKey: "id"); meta.removeValue(forKey: "title")
        index.pageOwner[item.id] = item.owner
        if isRegular(target), index.pages[item.id] != nil {
            let stored = index.pageMeta[item.id]
            let unchanged = stored == .object(meta)
            if unchanged && index.keys[item.id] == key { return }
            try beforeWrite(item.id, target)
            try rewriteHead(target, id: item.id, sortKey: key, page: unchanged ? stored! : .object(meta))
            try afterWrite(item.id, target)
            index.pageMeta[item.id] = .object(meta)
        } else {
            let pending = try pendingURL(item.id)
            let content: Data
            if isRegular(pending) { content = try Data(contentsOf: pending) }
            else {
                content = try JSON.object(["version": .number(2), "notebookId": .string(item.owner), "pageId": .string(item.id),
                                           "strokes": .array([]), "objects": .array([])]).data()
            }
            try atomic(PageFileFormat.compose(id: item.id, sortKey: key, page: .object(meta), content: content), to: target)
            if isRegular(pending) { try fm.removeItem(at: pending) }
            index.pages[item.id] = relative(target); index.pageMeta[item.id] = .object(meta)
            try afterWrite(item.id, target)
        }
    }

    private func moveIndex(_ from: String, to: String, page: Bool) {
        guard from != to else { return }
        func moved(_ path: String) -> String? { path == from || path.hasPrefix(from + "/") ? to + path.dropFirst(from.count) : nil }
        for (id, path) in index.pages { if let next = moved(path) { index.pages[id] = next } }
        if !page { for (id, path) in index.folders { if let next = moved(path) { index.folders[id] = next } } }
    }
    private func move(_ source: URL, to target: URL) throws {
        guard Darwin.rename(source.path, target.path) == 0 else { throw MojianError.invalid("无法移动 \(target.lastPathComponent)") }
    }

    /// Rewrites only the head; the content bytes stay as they are.
    public func rewriteHead(_ file: URL, id: String, sortKey: String, page: JSON) throws {
        let data = try Data(contentsOf: file)
        let content: Data
        if let prefix = PageFileFormat.headPrefix(data) {
            content = data.suffix(from: prefix.count + "\"content\":".utf8.count).dropLast()
        } else { content = try JSON.read(data)["content"].data() }
        try atomic(PageFileFormat.compose(id: id, sortKey: sortKey, page: page, content: content), to: file)
    }

    public func temporaryURL(_ file: URL) -> URL { url("\(FolderNames.hiddenRoot)/tmp/\(file.lastPathComponent).\(UUID().uuidString).tmp") }

    /// Durable replace that keeps the replaced bytes as the previous version.
    public func atomic(_ data: Data, to file: URL, keepPrevious: Bool = true) throws {
        guard !readOnly else { throw MojianError.invalid("当前笔记库为只读") }
        try fm.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        let temp = temporaryURL(file)
        try fm.createDirectory(at: temp.deletingLastPathComponent(), withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: temp) }
        guard fm.createFile(atPath: temp.path, contents: nil, attributes: [.posixPermissions: 0o600]) else { throw MojianError.invalid("无法创建保存临时文件") }
        let handle = try FileHandle(forWritingTo: temp)
        do { try handle.write(contentsOf: data); try handle.synchronize(); try handle.close() } catch { try? handle.close(); throw error }
        if keepPrevious, isRegular(file) {
            let previous = previousURL(file)
            try fm.createDirectory(at: previous.deletingLastPathComponent(), withIntermediateDirectories: true)
            if fm.fileExists(atPath: previous.path) { try fm.removeItem(at: previous) }
            try fm.copyItem(at: file, to: previous)
        }
        guard Darwin.rename(temp.path, file.path) == 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
    }
}
