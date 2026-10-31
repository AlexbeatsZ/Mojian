import XCTest
import AppKit
import MojianCore
@testable import MojianDesktop

final class StoreTests: XCTestCase {
    var temp: URL!
    override func setUpWithError() throws {
        temp = URL(fileURLWithPath: "/tmp/.agents/mojian-macos-tests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: temp, withIntermediateDirectories: true)
    }
    override func tearDownWithError() throws { try FileManager.default.removeItem(at: temp) }
    func create() throws -> CanonicalStore { try CanonicalStore(root: temp.appendingPathComponent("workspace"), create: true) }
    func testCanonicalV2AndSequentialRevisionPreserveUnknownFields() throws {
        let store = try create(), books = try store.loadLibrary(), page = books[0].pages[0]
        let index = try JSON.read(Data(contentsOf: store.root.appendingPathComponent("library.json")))
        XCTAssertEqual(index["version"], .number(2)); XCTAssertNotEqual(index["notebooks"].array[0]["sections"].array[0]["pages"].array[0]["appearance"], .null)
        var d = try store.loadPage(notebook: books[0].id, page: page.id)
        d.json["future"] = .object(["retain": .bool(true)]); d.strokes = [Settings().stroke([InkSample(x: 10, y: 20), InkSample(x: 70, y: 90)])]
        _ = try store.writePage(d); _ = try store.writePage(d)
        let reread = try store.loadPage(notebook: books[0].id, page: page.id)
        XCTAssertEqual(reread.json["revision"], .number(2)); XCTAssertEqual(reread.json["future"]["retain"], .bool(true))
    }
    func testExternalLibraryOpenIsBytePreservingAndRejectsWrites() throws {
        let writer = try create(), books = try writer.loadLibrary(), root = writer.root
        let index = try Data(contentsOf: root.appendingPathComponent("library.json"))
        let reader = try CanonicalStore(root: root), read = try reader.loadLibrary()
        let d = try reader.loadPage(notebook: read[0].id, page: read[0].pages[0].id)
        XCTAssertThrowsError(try reader.saveLibrary(books)); XCTAssertThrowsError(try reader.writePage(d))
        XCTAssertEqual(index, try Data(contentsOf: root.appendingPathComponent("library.json")))
    }
    func testExternalEditPreventsStaleOverwrite() throws {
        let store = try create(), n = try store.loadLibrary()[0], p = n.pages[0]
        let d = try store.loadPage(notebook: n.id, page: p.id), url = try store.checked("notebooks/\(n.id)/pages/\(p.id).json")
        var remote = d; remote.json["external"] = .string("newer")
        try remote.json.data().write(to: url, options: .atomic)
        XCTAssertThrowsError(try store.writePage(d)); XCTAssertEqual(try JSON.read(Data(contentsOf: url))["external"], .string("newer"))
    }
    func testRecoveryKeepsValidPreviousAndDoesNotWriteOnRead() throws {
        let store = try create(), n = try store.loadLibrary()[0], p = n.pages[0]
        var d = try store.loadPage(notebook: n.id, page: p.id); d.json["revision"] = .number(7)
        _ = try store.writePage(d, incrementRevision: false)
        let url = try store.checked("notebooks/\(n.id)/pages/\(p.id).json"), previous = url.appendingPathExtension("previous")
        let good = try Data(contentsOf: previous); try Data("corrupt".utf8).write(to: url)
        let recovered = try store.loadPage(notebook: n.id, page: p.id)
        XCTAssertEqual(try Data(contentsOf: url), Data("corrupt".utf8)); XCTAssertEqual(try Data(contentsOf: previous), good)
        _ = try store.writePage(recovered); XCTAssertEqual(try Data(contentsOf: previous), good)
    }
    func testMissingIndexedPageThrowsRatherThanBecomingBlank() throws {
        let store = try create(), n = try store.loadLibrary()[0]
        let url = try store.checked("notebooks/\(n.id)/pages/\(n.pages[0].id).json")
        try FileManager.default.removeItem(at: url)
        XCTAssertThrowsError(try store.loadPage(notebook: n.id, page: n.pages[0].id))
    }
    func testSymlinkAndTraversalCannotEscapeWorkspace() throws {
        let store = try create(), outside = temp.appendingPathComponent("outside")
        try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: true)
        try FileManager.default.createSymbolicLink(at: store.root.appendingPathComponent("linked"), withDestinationURL: outside)
        XCTAssertThrowsError(try store.checked("linked/page.json")); XCTAssertThrowsError(try store.checked("../outside/page.json"))
    }
    func testWriterLockAndSyncMarkerProtection() throws {
        let store = try create()
        XCTAssertThrowsError(try CanonicalStore(root: store.root, writable: true))
        try Data().write(to: store.root.appendingPathComponent(".stfolder"))
        XCTAssertThrowsError(try CanonicalStore(root: store.root, writable: true))
        XCTAssertNoThrow(try CanonicalStore(root: store.root))
    }
    func testUnknownHierarchyFieldsSurviveEditingAndAppearanceBridge() throws {
        let original: JSON = .object(["id": .string("book-test"), "title": .string("book"), "futureBook": .number(99), "sections": .array([
            .object(["id": .string("section-test"), "title": .string("section"), "futureSection": .bool(true), "pages": .array([]), "children": .array([])])])])
        let book = Notebook(original); book.title = "changed"; book.sections[0].title = "changed too"
        XCTAssertEqual(book.json["futureBook"], .number(99)); XCTAssertEqual(book.json["sections"].array[0]["futureSection"], .bool(true))
        let page = CanonicalStore.newPage(title: "A4", paper: "A4", background: "RULE_TWO_COLUMN_STANDARD"), before = page.json
        let display = AppearanceBridge.display(page, dark: false)
        XCTAssertEqual(display.paper, "A4"); XCTAssertEqual(display.view["backgroundFormat"], .string("RULE_TWO_COLUMN_STANDARD")); XCTAssertFalse(display.dark); XCTAssertEqual(page.json, before)
    }
    @MainActor func testCleanNavigationAndViewportDoNotWriteCanonicalBytes() async throws {
        let store = try create(), prefsName = "com.meta.mojian.tests.\(UUID().uuidString)", prefs = UserDefaults(suiteName: prefsName)!
        defer { prefs.removePersistentDomain(forName: prefsName) }
        let session = try DesktopSession(store: store, preferences: prefs), n = session.notebook!, p = session.page!
        let pageURL = try store.checked("notebooks/\(n.id)/pages/\(p.id).json"), indexURL = store.root.appendingPathComponent("library.json")
        let a = try Data(contentsOf: pageURL), b = try Data(contentsOf: indexURL)
        session.remember(Viewport(scale: 64, x: 200, y: -5000)); session.dark.toggle(); try session.select(n, session.section, p); try session.flush()
        XCTAssertEqual(a, try Data(contentsOf: pageURL)); XCTAssertEqual(b, try Data(contentsOf: indexURL))
    }
    @MainActor func testFailedSaveKeepsDirtyStateAndBlocksNavigation() async throws {
        let store = try create(), prefsName = "com.meta.mojian.tests.\(UUID().uuidString)", prefs = UserDefaults(suiteName: prefsName)!
        defer { prefs.removePersistentDomain(forName: prefsName) }
        let session = try DesktopSession(store: store, preferences: prefs), n = session.notebook!, p = session.page!
        session.mutate { $0.strokes.append(Settings().stroke([InkSample(x: 10, y: 30)])) }
        let url = try store.checked("notebooks/\(n.id)/pages/\(p.id).json")
        try Data("external".utf8).write(to: url, options: .atomic)
        XCTAssertThrowsError(try session.select(n, nil, nil)); XCTAssertTrue(session.dirty); XCTAssertEqual(session.page?.id, p.id)
        XCTAssertEqual(session.document?.strokes.count, 1); XCTAssertNotNil(session.saveError)
    }
    @MainActor func testNativeCanvasAndSharedBackgroundCatalog() async throws {
        let store = try create(), prefsName = "com.meta.mojian.tests.\(UUID().uuidString)", prefs = UserDefaults(suiteName: prefsName)!
        defer { prefs.removePersistentDomain(forName: prefsName) }
        let session = try DesktopSession(store: store, preferences: prefs), canvas = NoteCanvas(session: session)
        XCTAssertEqual(canvas.renderer.catalog["a4PageGap"], .number(32)); XCTAssertEqual(canvas.renderer.catalog["formats"].array.count, 14)
        var stroke = Settings().stroke([InkSample(x: 10, y: 10)]); stroke["opacityPercent"] = .number(32)
        XCTAssertEqual(canvas.renderer.opacity(stroke), 1)
        stroke["vectorPath"] = .string("F0 M 0,0 L 10,0 L 10,10 Z"); XCTAssertEqual(canvas.renderer.opacity(stroke), 0.32)
    }
    @MainActor func testArchiveRoundTripPublishesAssetsAndCoexistingNotebook() async throws {
        let store = try create(), prefsName = "com.meta.mojian.tests.\(UUID().uuidString)", prefs = UserDefaults(suiteName: prefsName)!
        defer { prefs.removePersistentDomain(forName: prefsName) }
        let session = try DesktopSession(store: store, preferences: prefs), original = session.notebook!.id, page = session.page!.id
        let bytes = Data([1, 3, 5, 7])
        try store.writeAsset(bytes, notebook: original, page: page, name: "sample.bin")
        session.mutate { $0.objects.append(.object(["id": .string("image-test"), "kind": .number(2), "x": .number(10), "y": .number(20), "width": .number(100), "height": .number(80), "assetName": .string("sample.bin")])) }
        try session.flush()
        let archive = temp.appendingPathComponent("notebook.mjn"), n = try session.exchangeNotebook()
        try NotebookArchive.export(n, store: WorkspaceStore(root: store.root), to: archive)
        try session.importNotebook(archive)
        XCTAssertEqual(session.notebooks.count, 2); XCTAssertNotEqual(session.notebook?.id, original)
        let d = session.document!, name = d.objects[0]["assetName"].string
        XCTAssertEqual(try Data(contentsOf: store.assetURL(name, document: d)), bytes)
        XCTAssertNotEqual(session.page?.json["appearance"], .null)
        XCTAssertEqual(try store.loadLibrary().count, 2)
    }
    @MainActor func testBackgroundSaveCompletionCannotClearNewerDirtyContent() async throws {
        let store = try create(), prefsName = "com.meta.mojian.tests.\(UUID().uuidString)", prefs = UserDefaults(suiteName: prefsName)!
        defer { prefs.removePersistentDomain(forName: prefsName) }
        let session = try DesktopSession(store: store, preferences: prefs)
        session.mutate { $0.strokes.append(Settings().stroke([InkSample(x: 20, y: 30)])) }
        try await Task.sleep(nanoseconds: 550_000_000)
        session.mutate { $0.strokes.append(Settings().stroke([InkSample(x: 80, y: 90)])) }
        try session.flush()
        try await Task.sleep(nanoseconds: 50_000_000)
        let d = try store.loadPage(notebook: session.notebook!.id, page: session.page!.id)
        XCTAssertEqual(d.strokes.count, 2); XCTAssertFalse(session.dirty)
        XCTAssertEqual(d.json["revision"], .number(2))
    }

}
