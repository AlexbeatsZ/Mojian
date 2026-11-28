import XCTest
import AppKit
import MojianCore
@testable import MojianDesktop

final class ReceiveFixture: ReadOnlySyncTransport, @unchecked Sendable {
    var remote: ReadOnlySyncManifest
    var bytes = [String: Data]()
    var downloaded = [String]()
    var requests = 0
    var unavailable = false
    var corrupt = false
    /// Every fixture library has the notebook folder `测试本`, as a server manifest always lists its folders.
    init(_ files: [String: Data?], revision: Int64 = 1, workspace: String = "readonly-fixture-workspace", folders: [String] = ["测试本"]) {
        remote = ReadOnlySyncManifest(workspaceId: workspace, revision: revision, files: [:])
        set(files, revision: revision)
        for folder in folders { remote.files[folder] = ReadOnlySyncEntry(hash: nil, version: revision, directory: true) }
    }
    func folder(_ path: String, live: Bool, revision: Int64) {
        remote.revision = revision
        remote.files[path] = ReadOnlySyncEntry(hash: nil, version: revision, directory: live ? true : nil)
    }
    func set(_ files: [String: Data?], revision: Int64) {
        remote.revision = revision
        for (path, value) in files {
            let hash = value.map(ReadOnlySyncFiles.digest)
            remote.files[path] = ReadOnlySyncEntry(hash: hash, version: revision)
            if let value, let hash { bytes[hash] = value }
        }
    }
    func manifest() async throws -> ReadOnlySyncManifest {
        requests += 1
        if unavailable { throw URLError(.cannotConnectToHost) }
        return remote
    }
    func download(_ hash: String) async throws -> URL {
        downloaded.append(hash)
        let url = URL(fileURLWithPath: "/tmp/.agents/readonly-blob-\(UUID().uuidString)")
        try (corrupt ? Data("corrupt".utf8) : bytes[hash]!).write(to: url)
        return url
    }
}

final class ReadOnlySyncTests: XCTestCase {
    var temp: URL!
    let pagePath = "测试本/页面.mojian"
    let index = ReadOnlySyncTests.index
    static let index = "测试本/.mojian-folder.json"
    func exists(_ path: String) -> Bool { FileManager.default.fileExists(atPath: temp.appendingPathComponent(path).path) }
    override func setUpWithError() throws {
        temp = URL(fileURLWithPath: "/tmp/.agents/mojian-receive-tests-\(UUID().uuidString)").resolvingSymlinksInPath()
        try FileManager.default.createDirectory(at: temp, withIntermediateDirectories: true)
        try Data("old-index".utf8).write(to: temp.appendingPathComponent("library.json"))
    }
    override func tearDownWithError() throws { try FileManager.default.removeItem(at: temp) }
    func config(_ fixture: ReceiveFixture, initial: Bool = true) -> ReadOnlySyncConfiguration {
        ReadOnlySyncConfiguration(endpoint: "http://127.0.0.1:1234/mojian-sync/v1/", token: "isolated-fixture-token", enabled: true,
                                  workspaceId: fixture.remote.workspaceId, allowInitialReplacement: initial)
    }
    func engine(_ fixture: ReceiveFixture, initial: Bool = true) throws -> ReadOnlySync {
        try ReadOnlySync(root: temp, configuration: config(fixture, initial: initial), factory: { _, _ in fixture })
    }
    func put(_ path: String, _ data: Data) throws {
        let url = temp.appendingPathComponent(path)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: .atomic)
    }
    func data(_ path: String) throws -> Data { try Data(contentsOf: temp.appendingPathComponent(path)) }
    func recoveryFiles() throws -> [URL] {
        let dir = temp.appendingPathComponent(".mojian-sync/macos-readonly/recovery")
        return FileManager.default.enumerator(at: dir, includingPropertiesForKeys: nil)?.allObjects.compactMap { $0 as? URL } ?? []
    }
    /// The first v3 receive into a v2 copy: tombstones delete the old files (kept in recovery), the empty v2
    /// folders disappear, and the copy becomes a folder library.
    func testInitialReceiveRetiresV2CopyPreservingReplacedAndDeletedFiles() async throws {
        let old = Data("old page".utf8), new = Data("new page".utf8)
        try put("notebooks/book/pages/page.json", old); try put("notebooks/book/pages/retired.json", old)
        try put("notebooks/book/pages/page.json.previous", old)
        XCTAssertFalse(FolderLibrary.isLibrary(temp))
        let fixture = ReceiveFixture(["library.json": nil, "notebooks/book/pages/page.json": nil, "notebooks/book/pages/retired.json": nil,
                                      index: Data("new-index".utf8), pagePath: new], folders: ["测试本", "测试本/空分区"])
        let receiver = try engine(fixture), result = try await receiver.synchronize()
        XCTAssertEqual(result.changed, 5); XCTAssertEqual(try data(pagePath), new)
        XCTAssertFalse(exists("library.json"))
        XCTAssertFalse(exists("notebooks/book/pages/retired.json"))
        XCTAssertTrue(exists("notebooks/book/pages/page.json.previous")) // an unsynchronized local file is never deleted
        XCTAssertTrue(exists("测试本/空分区"))
        XCTAssertTrue(FolderLibrary.isLibrary(temp))
        let recovery = try recoveryFiles()
        XCTAssertEqual(try recovery.filter { $0.lastPathComponent == "page.json" }.map { try Data(contentsOf: $0) }, [old])
        XCTAssertEqual(try recovery.filter { $0.lastPathComponent == "retired.json" }.map { try Data(contentsOf: $0) }, [old])
        let downloads = fixture.downloaded.count
        let settled = try await receiver.synchronize()
        XCTAssertEqual(settled.changed, 0); XCTAssertEqual(fixture.downloaded.count, downloads)
        let pending = await receiver.hasPendingIncoming; XCTAssertFalse(pending)
    }
    func testEmptyV2FoldersArePrunedAfterTheirFilesAreDeleted() async throws {
        try put("notebooks/book/pages/page.json", Data("old".utf8))
        let fixture = ReceiveFixture(["library.json": nil, "notebooks/book/pages/page.json": nil, index: Data("index".utf8)])
        _ = try await engine(fixture).synchronize()
        XCTAssertFalse(exists("notebooks"))
        XCTAssertEqual(try FolderLibrary(root: temp, readOnly: true).read()["notebooks"].array.map { $0["title"].string }, ["测试本"])
    }
    func testFoldersCaseOnlyRenamesAndUnsupportedLocalItems() async throws {
        let fixture = ReceiveFixture([index: Data("index".utf8), "测试本/note.mojian": Data("body".utf8)], folders: ["测试本", "测试本/分区"])
        let receiver = try engine(fixture); _ = try await receiver.synchronize()
        XCTAssertTrue(exists("测试本/分区"))
        fixture.set(["测试本/note.mojian": nil, "测试本/Note.mojian": Data("body".utf8)], revision: 2)
        fixture.folder("测试本/分区", live: false, revision: 3)
        try put("测试本/说明.txt", Data("local note".utf8))
        let result = try await receiver.synchronize()
        let names = try FileManager.default.contentsOfDirectory(atPath: temp.appendingPathComponent("测试本").path)
        XCTAssertTrue(names.contains("Note.mojian")); XCTAssertFalse(names.contains("note.mojian")); XCTAssertFalse(names.contains("分区"))
        XCTAssertEqual(try data("测试本/Note.mojian"), Data("body".utf8))
        XCTAssertEqual(result.skipped["测试本/说明.txt"], "不是墨笺页面或附件，未同步")
        XCTAssertEqual(try data("测试本/说明.txt"), Data("local note".utf8))
        let settled = try await receiver.synchronize(); XCTAssertEqual(settled.changed, 0)
    }
    func testRemoteDeletionIsReceivedAndNeverUploadedOrResurrected() async throws {
        let fixture = ReceiveFixture([index: Data("index".utf8), pagePath: Data("page".utf8)])
        let receiver = try engine(fixture); _ = try await receiver.synchronize()
        fixture.set([pagePath: nil], revision: 2)
        _ = try await receiver.synchronize()
        XCTAssertFalse(FileManager.default.fileExists(atPath: temp.appendingPathComponent(pagePath).path))
        let settled = try await receiver.synchronize()
        XCTAssertEqual(settled.changed, 0)
    }
    func testSharedBlobCanPublishMultiplePaths() async throws {
        let same = Data("shared immutable bytes".utf8), other = "测试本/另一页.mojian"
        let fixture = ReceiveFixture([index: Data("index".utf8), pagePath: same, other: same])
        _ = try await engine(fixture).synchronize()
        XCTAssertEqual(try data(pagePath), same); XCTAssertEqual(try data(other), same)
        XCTAssertEqual(fixture.downloaded.filter { $0 == ReadOnlySyncFiles.digest(same) }.count, 1)
    }
    func testFirstReplacementRequiresExplicitConfiguration() async throws {
        let fixture = ReceiveFixture([index: Data("index".utf8)])
        do { _ = try await engine(fixture, initial: false).synchronize(); XCTFail("unreviewed adoption") } catch { }
        XCTAssertEqual(try data("library.json"), Data("old-index".utf8)); XCTAssertTrue(fixture.downloaded.isEmpty)
    }
    func testUnexpectedLocalEditIsPreserved() async throws {
        let fixture = ReceiveFixture([index: Data("index".utf8), pagePath: Data("page".utf8)])
        let receiver = try engine(fixture); _ = try await receiver.synchronize()
        try put(pagePath, Data("unexpected edit".utf8))
        fixture.set([pagePath: Data("remote edit".utf8)], revision: 2)
        do { _ = try await receiver.synchronize(); XCTFail("overwrote local edit") } catch { }
        XCTAssertEqual(try data(pagePath), Data("unexpected edit".utf8))
    }
    func testCorruptBlobCannotPublishOrAdvanceCheckpoint() async throws {
        let fixture = ReceiveFixture([index: Data("index".utf8)]); fixture.corrupt = true
        let receiver = try engine(fixture)
        do { _ = try await receiver.synchronize(); XCTFail("accepted corrupt blob") } catch { }
        XCTAssertEqual(try data("library.json"), Data("old-index".utf8))
        let pending = await receiver.hasPendingIncoming; XCTAssertFalse(pending)
    }
    func testOfflineReplayFinishesPartiallyPublishedBatch() async throws {
        let index = Data("new-index".utf8), page = Data("new-page".utf8), fixture = ReceiveFixture([Self.index: index, pagePath: page])
        try put(pagePath, Data("old-page".utf8))
        let root = temp!, pagePath = pagePath
        do {
            let receiver = try engine(fixture)
            _ = try await receiver.synchronize(apply: { _ in
                // Simulate process loss after the first rename, before index/checkpoint publication.
                try page.write(to: root.appendingPathComponent(pagePath), options: .atomic)
                throw URLError(.cancelled)
            })
            XCTFail("lost publication must stay pending")
        } catch { }
        // A fresh engine must recover its disk journal without making a network request.
        let receiver = try engine(fixture)
        let pending = await receiver.hasPendingIncoming; XCTAssertTrue(pending)
        XCTAssertEqual(try data("library.json"), Data("old-index".utf8)); XCTAssertFalse(exists(Self.index))
        fixture.unavailable = true
        let requests = fixture.requests
        let result = try await receiver.recover()
        XCTAssertEqual(result?.revision, 1); XCTAssertEqual(fixture.requests, requests)
        XCTAssertEqual(try data(pagePath), page); XCTAssertEqual(try data(Self.index), index); XCTAssertFalse(exists("library.json"))
    }
    func testChangedPreconditionKeepsJournalAndBothVersions() async throws {
        let fixture = ReceiveFixture([index: Data("new-index".utf8)])
        let root = temp!, receiver = try engine(fixture)
        do {
            _ = try await receiver.synchronize(apply: { action in
                try Data("late local edit".utf8).write(to: root.appendingPathComponent("library.json"), options: .atomic)
                try action()
            }); XCTFail("late edit was overwritten")
        } catch { }
        XCTAssertEqual(try data("library.json"), Data("late local edit".utf8))
        do { _ = try await receiver.recover(); XCTFail("replay overwrote late edit") } catch { }
        let pending = await receiver.hasPendingIncoming; XCTAssertTrue(pending)
        XCTAssertTrue(FileManager.default.fileExists(atPath: temp.appendingPathComponent(".mojian-sync/macos-readonly/blobs/" + ReadOnlySyncFiles.digest(Data("new-index".utf8))).path))
    }
    func testHostCannotAcknowledgeWithoutExecutingPublication() async throws {
        let fixture = ReceiveFixture([index: Data("new-index".utf8)]), receiver = try engine(fixture)
        do { _ = try await receiver.synchronize(apply: { _ in }); XCTFail("acknowledged without publish") } catch { }
        XCTAssertEqual(try data("library.json"), Data("old-index".utf8))
        let result = try await receiver.recover(); XCTAssertEqual(result?.revision, 1)
    }
    func testIdentityAndRollbackCannotReplaceLocalBytes() async throws {
        let fixture = ReceiveFixture([index: Data("index".utf8)], revision: 2), receiver = try engine(fixture)
        _ = try await receiver.synchronize()
        fixture.remote.revision = 1
        do { _ = try await receiver.synchronize(); XCTFail("accepted rollback") } catch { }
        fixture.remote.revision = 3; fixture.remote.workspaceId = "different-fixture-workspace"
        do { _ = try await receiver.synchronize(); XCTFail("accepted identity change") } catch { }
        XCTAssertEqual(try data(index), Data("index".utf8))
    }
    func testOrderedFallbackAndIdentityFailureStopsFallback() async throws {
        let failed = ReceiveFixture([index: Data("index".utf8)]), good = ReceiveFixture([index: Data("index".utf8)])
        failed.unavailable = true
        var configuration = config(good)
        configuration.addresses = ["http://127.0.0.1:1234/mojian-sync/v1/", "http://127.0.0.1:1235/mojian-sync/v1/"]
        let receiver = try ReadOnlySync(root: temp, configuration: configuration, factory: { url, _ in url.port == 1234 ? failed : good })
        _ = try await receiver.synchronize(); XCTAssertEqual(failed.requests, 1); XCTAssertEqual(good.requests, 1)
        failed.unavailable = false; failed.remote.workspaceId = "different-fixture-workspace"
        do { _ = try await receiver.synchronize(); XCTFail("fell back after identity failure") } catch { }
        XCTAssertEqual(good.requests, 1)
    }
    func testInvalidPathsAndCaseCollisionsAreRefused() async throws {
        for path in ["../测试本/a.mojian", "测试本/CON.mojian", "测试本/a.mojian\n", ".mojian/assets/p/x.png\n", ".mojian/assets/p/x.previous",
                     "测试本/尾点./a.mojian", "测试本/a:b.mojian", ".mojian/navigation.json", "测试本/.hidden.mojian"] {
            XCTAssertFalse(ReadOnlySyncFiles.validPath(path), path)
        }
        for path in ["测试本/分区/页.mojian", ".mojian/assets/p/x.png", "测试本/.mojian-folder.json", "library.json"] { XCTAssertTrue(ReadOnlySyncFiles.validPath(path), path) }
        let fixture = ReceiveFixture([index: Data("index".utf8), "测试本/a.mojian": Data("a".utf8), "测试本/A.mojian": Data("b".utf8)])
        do { _ = try await engine(fixture).synchronize(); XCTFail("case collision accepted") } catch { }
        XCTAssertEqual(try data("library.json"), Data("old-index".utf8))
    }
    func testWriterLockAndSymlinkProtection() throws {
        let fixture = ReceiveFixture([index: Data("index".utf8)]), receiver = try engine(fixture)
        try withExtendedLifetime(receiver) { XCTAssertThrowsError(try engine(fixture)) }
        XCTAssertThrowsError(try ReadOnlySyncFiles.checked("../escape", root: temp))
        try FileManager.default.createSymbolicLink(at: temp.appendingPathComponent("linked"), withDestinationURL: temp)
        XCTAssertThrowsError(try ReadOnlySyncFiles.checked("linked/library.json", root: temp))
    }
    @MainActor func testMissingConfigurationCannotBypassStartupRecovery() async throws {
        let journal = temp.appendingPathComponent(".mojian-sync/macos-readonly/incoming.json")
        try FileManager.default.createDirectory(at: journal.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("pending".utf8).write(to: journal)
        XCTAssertThrowsError(try MacApplication().open(temp)) { error in
            XCTAssertTrue(error.localizedDescription.contains("恢复记录"))
        }
    }
    @MainActor func testReloadKeepsSelectionAndViewportAndRejectsEditing() async throws {
        let root = temp.appendingPathComponent("canonical")
        do { _ = try CanonicalStore(root: root, create: true) }
        let store = try CanonicalStore(root: root), prefsName = "com.meta.mojian.tests.\(UUID().uuidString)", prefs = UserDefaults(suiteName: prefsName)!
        defer { prefs.removePersistentDomain(forName: prefsName) }
        let session = try DesktopSession(store: store, preferences: prefs), page = session.page!, notebook = session.notebook!
        session.remember(Viewport(scale: 2, x: 40, y: 50))
        let url = try store.pageURL(notebook: notebook.id, page: page.id)
        var document = session.document!; document.strokes = [Settings().stroke([InkSample(x: 10, y: 20)])]
        // Another device's save: new content under the same page head.
        let head = try XCTUnwrap(PageFileFormat.headPrefix(Data(contentsOf: url)))
        try PageFileFormat.recompose(prefix: head, content: document.json.data()).write(to: url, options: .atomic)
        try session.reloadReadOnlyLibrary()
        XCTAssertEqual(session.page?.id, page.id); XCTAssertEqual(session.document?.strokes.count, 1)
        XCTAssertEqual(session.viewport().scale, 2); XCTAssertFalse(session.canEdit)
        let before = try Data(contentsOf: url)
        session.mutate { $0.strokes = [] }; try session.flush()
        XCTAssertEqual(try Data(contentsOf: url), before)
    }
    func testRealServerTransportReceivesUpdateAndTombstoneUsingOnlyGET() async throws {
        let script = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("tools/readonly-sync-fixture.mjs")
        let process = Process(), output = Pipe(), input = Pipe()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        process.arguments = ["node", script.path, temp.appendingPathComponent("server").path]
        process.standardOutput = output; process.standardInput = input
        try process.run()
        defer {
            try? input.fileHandleForWriting.write(contentsOf: Data("close\n".utf8))
            try? input.fileHandleForWriting.close()
            process.waitUntilExit()
        }
        func line() throws -> Data {
            var data = Data()
            while let byte = try output.fileHandleForReading.read(upToCount: 1), !byte.isEmpty {
                if byte[0] == 10 { return data }; data.append(byte)
            }
            throw URLError(.cannotConnectToHost)
        }
        let configuration = try JSONDecoder().decode(ReadOnlySyncConfiguration.self, from: line())
        let receiver = try ReadOnlySync(root: temp, configuration: configuration)
        _ = try await receiver.synchronize()
        XCTAssertEqual(try data("测试本/分区/页面.mojian"), Data("first-page".utf8))
        try input.fileHandleForWriting.write(contentsOf: Data("requests\n".utf8))
        let first = try JSONDecoder().decode([[String]].self, from: line())
        XCTAssertTrue(first.count >= 3); XCTAssertTrue(first.allSatisfy { $0[0] == "GET" })
        try input.fileHandleForWriting.write(contentsOf: Data("update\n".utf8)); _ = try line()
        _ = try await receiver.synchronize()
        XCTAssertEqual(try data(index), Data("second-index".utf8))
        XCTAssertFalse(exists("测试本/分区"))
        try input.fileHandleForWriting.write(contentsOf: Data("requests\n".utf8))
        let second = try JSONDecoder().decode([[String]].self, from: line())
        XCTAssertTrue(second.allSatisfy { $0[0] == "GET" })
    }
}
