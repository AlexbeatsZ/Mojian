import XCTest
import AppKit
import MojianCore
@testable import MojianDesktop

/// Hierarchy lifecycle, device-local UI state and generated icon resources behind the 0.2 desktop UI.
final class WorkspaceUITests: XCTestCase {
    var temp: URL!
    var prefsName = ""
    var prefs: UserDefaults!
    override func setUpWithError() throws {
        temp = URL(fileURLWithPath: "/tmp/.agents/mojian-macos-ui-tests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: temp, withIntermediateDirectories: true)
        prefsName = "com.meta.mojian.tests.\(UUID().uuidString)"; prefs = UserDefaults(suiteName: prefsName)!
    }
    override func tearDownWithError() throws {
        prefs.removePersistentDomain(forName: prefsName)
        try FileManager.default.removeItem(at: temp)
    }
    /// Deleted files go to a private folder instead of the user's Trash.
    @MainActor func session() throws -> DesktopSession {
        let store = try CanonicalStore(root: temp.appendingPathComponent("workspace"), create: true)
        let trash = temp.appendingPathComponent("trash")
        try FileManager.default.createDirectory(at: trash, withIntermediateDirectories: true)
        store.discard = { try FileManager.default.moveItem(at: $0, to: trash.appendingPathComponent(UUID().uuidString)) }
        return try DesktopSession(store: store, preferences: prefs)
    }
    @MainActor func reloaded(_ s: DesktopSession) throws -> [Notebook] { try CanonicalStore(root: s.store.root).loadLibrary() }

    @MainActor func testRenameAndDeleteCommitIndexBeforeMovingFiles() async throws {
        let s = try session(), n = s.notebook!
        try s.addSection(title: "化学", in: n, parent: nil); let chemistry = s.section!
        try s.addPage(title: "反应", paper: "A4", in: chemistry); let page = s.page!
        s.mutate { $0.strokes.append(Settings().stroke([InkSample(x: 10, y: 10), InkSample(x: 40, y: 40)])) }
        try s.flush()
        try s.rename(n, "主笔记本"); try s.rename(chemistry, "无机化学"); try s.rename(page, "速率")
        var books = try reloaded(s)
        XCTAssertEqual(books[0].title, "主笔记本"); XCTAssertEqual(books[0].sections.last?.title, "无机化学"); XCTAssertEqual(books[0].sections.last?.pages[0].title, "速率")
        let file = try s.store.checked("notebooks/\(n.id)/pages/\(page.id).json")
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        try s.delete(page, from: chemistry, in: n)
        books = try reloaded(s)
        XCTAssertTrue(books[0].sections.last?.pages.isEmpty ?? false); XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
        XCTAssertNotEqual(s.page?.id, page.id); XCTAssertFalse(s.canUndo)
        try s.delete(chemistry, in: n)
        XCTAssertEqual(try reloaded(s)[0].sections.count, 1)
    }
    @MainActor func testDeletingLastNotebookLeavesFreshEmptyNotebook() async throws {
        let s = try session(), n = s.notebook!
        try s.delete(n)
        let books = try reloaded(s)
        XCTAssertEqual(books.count, 1); XCTAssertNotEqual(books[0].id, n.id); XCTAssertTrue(books[0].pages.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: s.store.root.appendingPathComponent("notebooks/\(n.id)").path))
        XCTAssertTrue(s.notebook === s.notebooks[0])
    }
    @MainActor func testReadOnlyLibraryRefusesHierarchyEdits() async throws {
        let writable = try session(), root = writable.store.root
        let index = try Data(contentsOf: root.appendingPathComponent("library.json"))
        let reader = try DesktopSession(store: CanonicalStore(root: root), preferences: prefs), n = reader.notebook!
        XCTAssertThrowsError(try reader.rename(n, "x")); XCTAssertThrowsError(try reader.delete(n))
        XCTAssertEqual(index, try Data(contentsOf: root.appendingPathComponent("library.json")))
    }
    @MainActor func testPresetsAreAddOnlyAndDeviceLocal() async throws {
        let s = try session(), count = s.presets.count
        s.addPreset(.pen)
        XCTAssertEqual(s.presets.count, count + 1); XCTAssertEqual(s.active.tool, .pen); XCTAssertTrue(s.mouseWriting)
        var p = s.active; p.color = 2; p.widthMM = 1.2; s.update(p)
        XCTAssertEqual(s.settings.color, 2); XCTAssertEqual(s.settings.widthMM, 1.2)
        let highlighter = s.presets.first { $0.tool == .highlighter }!
        s.apply(highlighter); XCTAssertEqual(s.settings.tool, .highlighter); XCTAssertEqual(s.settings.highlighterWidthMM, highlighter.widthMM)
        let restored = try DesktopSession(store: CanonicalStore(root: s.store.root), preferences: prefs)
        XCTAssertEqual(restored.presets, s.presets); XCTAssertEqual(restored.active.id, highlighter.id)
        // Pens added later stay inside the pen group, before shape and hand.
        XCTAssertEqual(s.presets.lastIndex { $0.tool == .pen }! + 1, s.presets.firstIndex { $0.tool == .shape }!)
    }
    @MainActor func testExpansionAndAppearanceEdits() async throws {
        let s = try session(), n = s.notebook!, index = s.store.root.appendingPathComponent("library.json")
        let before = try Data(contentsOf: index)
        s.toggleCollapsed(n.sections[0].id)
        XCTAssertEqual(before, try Data(contentsOf: index))
        XCTAssertTrue(try DesktopSession(store: CanonicalStore(root: s.store.root), preferences: prefs).isCollapsed(n.sections[0].id))
        try s.setPaper("A4"); try s.setTitleVisible(false); try s.setBackground("DOTS_WIDE")
        let page = AppearanceBridge.display(try reloaded(s)[0].pages[0])
        XCTAssertEqual(page.paper, "A4"); XCTAssertEqual(page.view["pageTitleVisible"], .bool(false)); XCTAssertEqual(page.view["backgroundFormat"], .string("DOTS_WIDE"))
    }
    @MainActor func testTextFormattingAndClearPageKeepBackgrounds() async throws {
        let s = try session()
        s.mutate { d in
            d.texts.append(.object(["id": .string("t1"), "text": .string("a"), "x": .number(0), "y": .number(0), "width": .number(100), "height": .number(40), "fontSize": .number(16)]))
            d.objects.append(.object(["id": .string("pdf-bg"), "kind": .number(2), "isPageBackground": .bool(true), "x": .number(0), "y": .number(0), "width": .number(10), "height": .number(10)]))
        }
        s.formatTexts(["t1"]) { $0["bold"] = .bool(true); $0["alignment"] = .string("Center") }
        XCTAssertEqual(s.document?.texts[0]["bold"], .bool(true)); XCTAssertEqual(s.document?.texts[0]["alignment"], .string("Center"))
        s.clearPage()
        XCTAssertTrue(s.document?.texts.isEmpty ?? false); XCTAssertEqual(s.document?.objects.count, 1)
        s.undo(); XCTAssertEqual(s.document?.texts.count, 1)
    }
    @MainActor func testGeneratedFluentIconsMatchAndroidSources() async throws {
        let names = ["pen", "highlight", "eraser", "lasso", "notebook", "chevron_right", "caret_down", "rule_lines", "arrow_redo"]
        for name in names { XCTAssertFalse(FluentIcon.catalog[name]["paths"].array.isEmpty, name) }
        // The redo vector is the mirrored undo group; its first point must be on the right side.
        XCTAssertTrue(FluentIcon.catalog["arrow_redo"]["paths"].array[0]["d"].string.hasPrefix("M 19.25 2"))
        XCTAssertFalse(FluentIcon.catalog["rule_lines"]["paths"].array.allSatisfy { $0["color"].string.isEmpty })
        let box = FluentIcon.path("M 2 3 L 10 3 C 12 3 12 9 10 9 Z").boundingBoxOfPath
        XCTAssertEqual(box.minX, 2, accuracy: 0.01); XCTAssertEqual(box.minY, 3, accuracy: 0.01)
    }
}
