import XCTest
@testable import MojianCore

final class ArchitectureRepairTests: XCTestCase {
    func scratch() throws -> URL {
        let url = URL(fileURLWithPath: "/private/tmp/.agents/mojian-core-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }
    func fixture(_ name: String) throws -> Data {
        let shared = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        return try Data(contentsOf: shared.appendingPathComponent("fixtures/workspace-v2/\(name).json"))
    }
    func testGoldenHierarchyAndAppearanceAreSharedAndLossless() throws {
        let root = try scratch()
        try fixture("library").write(to: root.appendingPathComponent("library.json"))
        try Data().write(to: root.appendingPathComponent(".mojian-macos-library"))
        let store = try CanonicalStore(root: root, writable: true)
        let books = try store.loadLibrary()
        let page = books[0].pages[0]
        XCTAssertEqual(page.paper, "BOUNDLESS")
        XCTAssertEqual(page.view["backgroundFormat"], .string("GRID_STANDARD"))
        books[0].title = "renamed"
        books[0].sections[0].expanded = false
        try store.saveLibrary(books)
        let saved = try JSON.read(Data(contentsOf: root.appendingPathComponent("library.json")))
        XCTAssertEqual(saved["futureLibrary"]["kept"], .bool(true))
        XCTAssertEqual(saved["notebooks"].array[0]["futureNotebook"], .string("retained"))
        XCTAssertEqual(saved["notebooks"].array[0]["sections"].array[0]["expanded"], .bool(true))
        XCTAssertEqual(saved["notebooks"].array[0]["sections"].array[0]["pages"].array[0]["appearance"]["futureAppearance"], .string("kept"))
    }
    func testNewCanonicalPagesHaveOneAppearanceRepresentation() {
        let page = CanonicalStore.newPage(title: "new", paper: "A4")
        XCTAssertNotEqual(page.json["appearance"], .null)
        XCTAssertEqual(page.json["viewSettings"], .null)
        XCTAssertEqual(page.paper, "A4")
        page.view["backgroundFormat"] = .string("RULE_TWO_COLUMN_WIDE")
        XCTAssertEqual(page.json["appearance"]["background"], .number(12))
    }
    func testWritableRecoveryPreservesCorruptVersionAndReadonlyLeavesSourceUntouched() throws {
        let root = try scratch().appendingPathComponent("workspace")
        let store = try CanonicalStore(root: root, create: true)
        let book = try store.loadLibrary()[0]
        let page = book.pages[0]
        var document = try store.loadPage(notebook: book.id, page: page.id)
        document.texts = [.object(["id": .string("text"), "text": .string("edit")])]
        try store.writePage(document)
        let url = try store.checked("notebooks/\(book.id)/pages/\(page.id).json")
        try Data("broken".utf8).write(to: url)
        let reader = try CanonicalStore(root: root)
        _ = try reader.loadPage(notebook: book.id, page: page.id)
        XCTAssertEqual(try String(contentsOf: url), "broken")
        let recovered = try store.loadPage(notebook: book.id, page: page.id)
        _ = try store.writePage(recovered)
        XCTAssertNotEqual(try String(contentsOf: url), "broken")
        let files = FileManager.default.enumerator(at: root.appendingPathComponent(".mojian-corrupt.previous"), includingPropertiesForKeys: nil)!
        XCTAssertTrue(files.allObjects.compactMap { $0 as? URL }.contains { (try? String(contentsOf: $0)) == "broken" })
    }
    func testCompletedInkIncludesPortableFilledOutline() throws {
        let record = Settings().completedStroke([InkSample(x: 10, y: 20), InkSample(x: 40, y: 60, elapsed: 8)])
        XCTAssertTrue(record["vectorPath"].string.hasPrefix("F1 "))
        let path = try InkPath.parse(record["vectorPath"].string)
        XCTAssertFalse(path.boundingBoxOfPath.isNull)
        XCTAssertEqual(record["vectorBounds"].array.count, 4)
    }
}
