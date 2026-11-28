import XCTest
@testable import MojianCore

final class FolderLibraryTests: XCTestCase {
    private var shared: URL { URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent() }
    private func scratch() throws -> URL {
        let url = URL(fileURLWithPath: "/private/tmp/.agents/mojian-folder-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }

    /// Names and keys must match tools/folder-library, Windows and Android exactly (shared/fixtures/folder-library.json).
    func testNamesAndOrderKeysMatchTheSharedFixture() throws {
        let fixture = try JSON.read(Data(contentsOf: shared.appendingPathComponent("fixtures/folder-library.json")))
        for item in fixture["names"].array { XCTAssertEqual(FolderNames.storable(item["title"].string), item["name"].string, item["title"].string) }
        var previous: String?
        for key in fixture["sequence"].array.map(\.string) {
            XCTAssertEqual(FractionalIndex.between(previous, nil), key); previous = key
        }
        for item in fixture["between"].array {
            let a = item["a"] == .null ? nil : item["a"].string, b = item["b"] == .null ? nil : item["b"].string
            XCTAssertEqual(FractionalIndex.between(a, b), item["key"].string, "\(a ?? "nil") \(b ?? "nil")")
        }
        var taken = Set<String>()
        XCTAssertEqual(FolderNames.unique("页", suffix: ".mojian", taken: &taken), "页.mojian")
        XCTAssertEqual(FolderNames.unique("页", suffix: ".mojian", taken: &taken), "页 (2).mojian")
        XCTAssertTrue(FolderNames.represents("页 (2).mojian", title: "页", suffix: ".mojian"))
        XCTAssertFalse(FolderNames.represents("页 (1).mojian", title: "页", suffix: ".mojian"))
    }

    func testRenamesMovesNewPagesAndDeletionsBecomeFolderOperations() throws {
        let root = try scratch().appendingPathComponent("library")
        let store = try CanonicalStore(root: root, create: true)
        var discarded = [String]()
        store.discard = { discarded.append($0.lastPathComponent); try FileManager.default.removeItem(at: $0) }
        let books = try store.loadLibrary()
        let book = books[0], section = book.sections[0], first = section.pages[0]
        let second = CanonicalStore.newPage(title: "第二页"), third = CanonicalStore.newPage(title: "第二页")
        try store.writePage(PageDocument(notebook: book.id, page: second.id), incrementRevision: false)
        section.pages += [second, third]
        let child = Section(title: "子/分区")
        section.children = [child]
        try store.saveLibrary(books)
        let base = root.appendingPathComponent(book.title).appendingPathComponent(section.title)
        XCTAssertTrue(FileManager.default.fileExists(atPath: base.appendingPathComponent("第二页.mojian").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: base.appendingPathComponent("第二页 (2).mojian").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: base.appendingPathComponent("子／分区/.mojian-folder.json").path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: try store.folder.pendingURL(second.id).path))

        // Move a page into the child section and rename the section: content bytes travel with the file.
        var document = try store.loadPage(notebook: book.id, page: first.id)
        document.texts = [.object(["id": .string("t"), "text": .string("kept")])]
        try store.writePage(document)
        section.pages.removeAll { $0.id == first.id }
        child.pages = [first]
        section.title = "改名分区"
        try store.saveLibrary(books)
        let moved = root.appendingPathComponent(book.title).appendingPathComponent("改名分区/子／分区/开始书写.mojian")
        XCTAssertEqual(try store.pageURL(notebook: book.id, page: first.id).path, moved.path)
        XCTAssertEqual(try store.loadPage(notebook: book.id, page: first.id).texts.first?["text"], .string("kept"))

        section.pages.removeAll { $0.id == third.id }
        try store.saveLibrary(books)
        XCTAssertEqual(discarded, ["第二页 (2).mojian"])
        let reread = try CanonicalStore(root: root).loadLibrary()
        XCTAssertEqual(reread[0].sections[0].title, "改名分区")
        XCTAssertEqual(reread[0].sections[0].pages.map(\.id), [second.id])
        XCTAssertEqual(reread[0].sections[0].children[0].pages.map(\.id), [first.id])
    }

    func testReadOnlyNeverWritesAndAdoptsUnmarkedFoldersWithStableIds() throws {
        let root = try scratch()
        try FileManager.default.copyItem(at: shared.appendingPathComponent("fixtures/workspace-v3"), to: root.appendingPathComponent("lib"))
        let library = root.appendingPathComponent("lib")
        try FileManager.default.createDirectory(at: library.appendingPathComponent("共享样例/文件管理器建的分区"), withIntermediateDirectories: true)
        func snapshot() -> [String] { (FileManager.default.enumerator(atPath: library.path)?.allObjects as? [String] ?? []).sorted() }
        let before = snapshot()
        let first = try CanonicalStore(root: library).loadLibrary()
        let second = try CanonicalStore(root: library).loadLibrary()
        XCTAssertEqual(snapshot(), before)
        let adopted = first[0].sections.first { $0.title == "文件管理器建的分区" }!
        XCTAssertEqual(adopted.id, second[0].sections.first { $0.title == "文件管理器建的分区" }!.id)
        XCTAssertEqual(try CanonicalStore(root: library).loadPage(notebook: "book.dot", page: "page.dot").json["revision"], .number(5))
    }
}
