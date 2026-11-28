import XCTest
@testable import MojianCore

final class ArchitectureRepairTests: XCTestCase {
    func scratch() throws -> URL {
        let url = URL(fileURLWithPath: "/private/tmp/.agents/mojian-core-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }
    /// The shared v3 sample library (shared/fixtures/workspace-v3) as a Mac-writable library.
    func v3Library() throws -> URL {
        let shared = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let root = try scratch().appendingPathComponent("library")
        try FileManager.default.copyItem(at: shared.appendingPathComponent("fixtures/workspace-v3"), to: root)
        try Data().write(to: root.appendingPathComponent(".mojian-macos-library"))
        return root
    }
    let goldenPage = "共享样例/样例分区/黄金页面.mojian"
    func testGoldenHierarchyAndAppearanceAreSharedAndLossless() throws {
        let root = try v3Library()
        let store = try CanonicalStore(root: root, writable: true)
        store.discard = { try FileManager.default.removeItem(at: $0) }
        let books = try store.loadLibrary()
        XCTAssertEqual(books.map(\.title), ["共享样例"])
        let page = books[0].pages[0]
        XCTAssertEqual(page.paper, "BOUNDLESS")
        XCTAssertEqual(page.view["backgroundFormat"], .string("GRID_STANDARD"))
        books[0].title = "renamed"
        books[0].sections[0].expanded = false
        try store.saveLibrary(books)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("共享样例").path))
        let notebook = try JSON.read(Data(contentsOf: root.appendingPathComponent("renamed/.mojian-folder.json")))
        XCTAssertEqual(notebook["id"], .string("book.dot"))
        XCTAssertEqual(notebook["futureNotebook"], .string("retained"))
        let section = try JSON.read(Data(contentsOf: root.appendingPathComponent("renamed/样例分区/.mojian-folder.json")))
        XCTAssertEqual(section["futureSection"], .number(7))
        XCTAssertEqual(section["expanded"], .null) // navigation stays on this device
        let head = try PageFileFormat.readHead(root.appendingPathComponent("renamed/样例分区/黄金页面.mojian"))
        XCTAssertEqual(head.id, "page.dot")
        XCTAssertEqual(head.page["appearance"]["futureAppearance"], .string("kept"))
        XCTAssertEqual(try CanonicalStore(root: root).loadLibrary()[0].title, "renamed")
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
        let url = try store.pageURL(notebook: book.id, page: page.id)
        XCTAssertEqual(url.lastPathComponent, "开始书写.mojian")
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
    func testPackedPointsMatchGoldenVectorsAndRoundTripThroughTheStore() throws {
        let shared = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let cases = try JSON.read(Data(contentsOf: shared.appendingPathComponent("fixtures/points-coded.json")))["cases"].array
        let scales = Dictionary(uniqueKeysWithValues: zip(InkPoints.names, InkPoints.scales))
        for item in cases {
            let name = item["name"].string, points = item["points"].array
            let coded: String? = item["coded"] == .null ? nil : item["coded"].string
            XCTAssertEqual(InkPoints.encode(points), coded, name)
            guard let coded else { continue }
            let decoded = try InkPoints.decode(coded)
            XCTAssertEqual(decoded.count, points.count, name)
            for (expected, actual) in zip(points, decoded) {
                XCTAssertEqual(Set(expected.dictionary.keys), Set(actual.dictionary.keys), name)
                for (key, value) in expected.dictionary {
                    XCTAssertEqual(value.number(), actual[key].number(), accuracy: 0.5 / scales[key]! + 1e-12, "\(name).\(key)")
                }
            }
            XCTAssertEqual(InkPoints.encode(decoded), coded, name)
        }
        XCTAssertThrowsError(try InkPoints.decode("1:AQ=="))
        XCTAssertThrowsError(try InkPoints.decode("2:AA=="))

        let root = try v3Library()
        let page = root.appendingPathComponent(goldenPage)
        let head = try PageFileFormat.headPrefix(Data(contentsOf: page))
        let store = try CanonicalStore(root: root, writable: true)
        _ = try store.loadLibrary()
        var document = try store.loadPage(notebook: "book.dot", page: "page.dot")
        document.strokes.append(Settings().completedStroke((0..<30).map { InkSample(x: Double($0) * 1.3, y: 7, pressure: 0.4, elapsed: Double($0 * 4)) }))
        let returned = try store.writePage(document)
        XCTAssertEqual(returned.strokes[1]["points"].array.count, 30)
        // Content goes under the head bytes exactly as another device wrote them.
        XCTAssertEqual(try PageFileFormat.headPrefix(Data(contentsOf: page)), head)
        let disk = try JSON.read(Data(contentsOf: page))["content"]["strokes"].array
        XCTAssertNotEqual(disk[0]["points"], .null) // per-point extension field keeps the array
        XCTAssertEqual(disk[1]["points"], .null)
        XCTAssertTrue(disk[1]["pointsCoded"].string.hasPrefix(InkPoints.prefix))
        let reloaded = try store.loadPage(notebook: "book.dot", page: "page.dot")
        XCTAssertEqual(reloaded.strokes[1]["points"].array.last?["x"].number() ?? 0, 29 * 1.3, accuracy: 0.0006)
        XCTAssertEqual(reloaded.strokes[1]["pointsCoded"], .null)
    }
    func testCompletedInkIncludesPortableFilledOutline() throws {
        let record = Settings().completedStroke([InkSample(x: 10, y: 20), InkSample(x: 40, y: 60, elapsed: 8)])
        XCTAssertTrue(record["vectorPath"].string.hasPrefix("F1 "))
        let path = try InkPath.parse(record["vectorPath"].string)
        XCTAssertFalse(path.boundingBoxOfPath.isNull)
        XCTAssertEqual(record["vectorBounds"].array.count, 4)
    }
    func testCompletedInkOutlineIsCompactAndKeepsItsShape() throws {
        let samples = (0..<120).map { i -> InkSample in
            let t = Double(i) / 119
            return InkSample(x: 20 + t * 140 + 12 * sin(t * 19), y: 50 + 18 * sin(t * 7), pressure: 0.2 + 0.75 * abs(sin(t * 5)), elapsed: Double(i * 4))
        }
        let record = Settings().completedStroke(samples)
        let text = record["vectorPath"].string
        let source = InkPath.make(Settings().stroke(samples))
        var full = 0
        source.applyWithBlock { _ in full += 1 }
        // Old form: one full-precision element ~ 40+ characters; compact stays well under 18 per element.
        XCTAssertLessThan(text.count, full * 18)
        XCTAssertNil(text.range(of: "\\.\\.|[eE]|[0-9]\\.[0-9]{3}", options: .regularExpression), text.prefix(200).description)
        let parsed = try InkPath.parse(text)
        let a = parsed.boundingBoxOfPath, b = source.boundingBoxOfPath
        XCTAssertEqual(a.minX, b.minX, accuracy: 0.02); XCTAssertEqual(a.maxY, b.maxY, accuracy: 0.02)
        // Interior and exterior probes agree with the full-precision geometry.
        for i in stride(from: 0, to: samples.count, by: 7) {
            let p = CGPoint(x: samples[i].x, y: samples[i].y)
            XCTAssertEqual(parsed.contains(p, using: .winding), source.contains(p, using: .winding))
            let outside = CGPoint(x: p.x, y: p.y + 30)
            XCTAssertEqual(parsed.contains(outside, using: .winding), source.contains(outside, using: .winding))
        }
    }
}
