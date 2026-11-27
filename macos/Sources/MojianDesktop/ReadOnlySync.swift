import Foundation
import Darwin
import MojianCore

struct ReadOnlySyncState: Codable, Sendable {
    var endpoint: String
    var manifest: ReadOnlySyncManifest
    var stamps: [String: ReadOnlySyncStamp]
}
struct ReadOnlySyncChange: Codable, Sendable {
    var expectedHash: String?
    var targetHash: String?
}
struct ReadOnlySyncIncoming: Codable, Sendable {
    var operationId: String
    var endpoint: String
    var manifest: ReadOnlySyncManifest
    var changes: [String: ReadOnlySyncChange]
}
struct ReadOnlySyncResult: Codable, Sendable {
    var revision: Int64
    var changed: Int
    var liveFiles: Int
}

/// Receive-only by construction: this engine can only fetch manifests and immutable blobs.
/// The actor owns one pass; the OS lock excludes other processes using the same receiver.
actor ReadOnlySync {
    typealias Apply = @Sendable (@escaping @Sendable () throws -> Void) async throws -> Void
    typealias Progress = @Sendable (String) async -> Void
    let root: URL
    private let directory: URL
    private let configuration: ReadOnlySyncConfiguration
    private let fm = FileManager.default
    private var descriptor: Int32 = -1
    private var busy = false
    private var state: ReadOnlySyncState?
    private let factory: @Sendable (URL, ReadOnlySyncConfiguration) -> any ReadOnlySyncTransport

    init(root: URL, configuration: ReadOnlySyncConfiguration,
         factory: @escaping @Sendable (URL, ReadOnlySyncConfiguration) -> any ReadOnlySyncTransport = { ReadOnlySyncHTTP(base: $0, configuration: $1) }) throws {
        self.root = root.standardizedFileURL.resolvingSymlinksInPath()
        guard self.root == root.standardizedFileURL else { throw MojianError.invalid("同步笔记库不允许符号链接") }
        self.configuration = configuration; self.factory = factory
        _ = try configuration.routes()
        guard fm.fileExists(atPath: self.root.appendingPathComponent("library.json").path),
              !fm.fileExists(atPath: self.root.appendingPathComponent(".mojian-macos-library").path),
              !fm.fileExists(atPath: self.root.appendingPathComponent(".mojian-native-workspace").path),
              !fm.fileExists(atPath: self.root.appendingPathComponent(".mojian-sync/state.json").path) else {
            throw MojianError.invalid("只读同步只能连接独立的只读副本")
        }
        var ancestor = self.root
        while ancestor.path != "/" {
            guard !fm.fileExists(atPath: ancestor.appendingPathComponent(".stfolder").path) else { throw MojianError.invalid("不能在 Syncthing 笔记库启用只读同步") }
            ancestor.deleteLastPathComponent()
        }
        directory = try ReadOnlySyncFiles.checked(".mojian-sync/macos-readonly", root: self.root)
        try fm.createDirectory(at: directory, withIntermediateDirectories: true)
        let lock = directory.appendingPathComponent("lock")
        let opened = Darwin.open(lock.path, O_CREAT | O_RDWR | O_NOFOLLOW, S_IRUSR | S_IWUSR)
        guard opened >= 0 else { throw MojianError.invalid("无法锁定只读同步笔记库") }
        guard flock(opened, LOCK_EX | LOCK_NB) == 0 else { Darwin.close(opened); throw MojianError.invalid("另一个墨笺进程正在同步此笔记库") }
        descriptor = opened
        do {
            let file = directory.appendingPathComponent("state.json")
            if fm.fileExists(atPath: file.path) {
                let loaded = try JSONDecoder().decode(ReadOnlySyncState.self, from: Data(contentsOf: file))
                guard loaded.endpoint == configuration.endpoint else { throw MojianError.invalid("只读同步检查点地址不符") }
                try loaded.manifest.validate(expectedWorkspace: configuration.workspaceId, previous: nil)
                state = loaded
            }
        } catch { flock(opened, LOCK_UN); Darwin.close(opened); descriptor = -1; throw error }
    }
    deinit { if descriptor >= 0 { flock(descriptor, LOCK_UN); Darwin.close(descriptor) } }
    private var incomingURL: URL { directory.appendingPathComponent("incoming.json") }
    var hasPendingIncoming: Bool { fm.fileExists(atPath: incomingURL.path) }

    func recover(apply: Apply = { try $0() }) async throws -> ReadOnlySyncResult? {
        guard !busy else { throw MojianError.invalid("只读同步正在进行") }
        busy = true; defer { busy = false }
        return try await recoverPending(apply: apply)
    }
    private func recoverPending(apply: Apply) async throws -> ReadOnlySyncResult? {
        guard hasPendingIncoming else { return nil }
        let incoming = try JSONDecoder().decode(ReadOnlySyncIncoming.self, from: Data(contentsOf: incomingURL))
        try validate(incoming)
        return try await publish(incoming, apply: apply)
    }

    func synchronize(apply: Apply = { try $0() }, progress: Progress = { _ in }) async throws -> ReadOnlySyncResult {
        guard configuration.enabled else { throw MojianError.invalid("只读同步已暂停") }
        guard !busy else { throw MojianError.invalid("只读同步正在进行") }
        busy = true; defer { busy = false }
        _ = try await recoverPending(apply: apply)
        await progress("正在检查笔记更新 · 只读")
        var route: (any ReadOnlySyncTransport)?
        var remote: ReadOnlySyncManifest?
        for address in try configuration.routes() {
            try Task.checkCancellation()
            let candidate = factory(address, configuration)
            do {
                remote = try await candidate.manifest(); route = candidate; break
            } catch {
                (candidate as? ReadOnlySyncHTTP)?.close()
                if Task.isCancelled { throw CancellationError() }
                // Only transport/HTTP failures can select another route. Invalid JSON is a hard error.
                guard error is URLError || error is ReadOnlySyncTransportError else { throw error }
            }
        }
        guard let transport = route, let remote else { throw MojianError.invalid("暂时无法连接同步服务器，将自动重试") }
        defer { (transport as? ReadOnlySyncHTTP)?.close() }
        try remote.validate(expectedWorkspace: configuration.workspaceId, previous: state?.manifest)
        let local = try scan()
        if state == nil && !configuration.allowInitialReplacement { throw MojianError.invalid("此副本尚未确认接收服务器版本") }
        var changes = [String: ReadOnlySyncChange]()
        for path in Set(remote.files.keys).union(local.keys) {
            let current = local[path]?.hash, target = remote.files[path]?.hash
            if let state, current != state.manifest.files[path]?.hash && current != target {
                throw MojianError.invalid("本地文件发生意外修改，已保留并停止接收：\(path)")
            }
            if current != target { changes[path] = ReadOnlySyncChange(expectedHash: current, targetHash: target) }
        }
        if changes.isEmpty {
            let next = ReadOnlySyncState(endpoint: configuration.endpoint, manifest: remote, stamps: local.mapValues(\.stamp))
            try ReadOnlySyncFiles.write(next, to: localURL("state.json")); state = next
            return ReadOnlySyncResult(revision: remote.revision, changed: 0, liveFiles: remote.files.values.filter { $0.hash != nil }.count)
        }
        let blobs = try localURL("blobs")
        try fm.createDirectory(at: blobs, withIntermediateDirectories: true)
        let hashes = Set(changes.values.compactMap(\.targetHash)).sorted()
        for (index, hash) in hashes.enumerated() {
            try Task.checkCancellation()
            await progress("正在接收笔记 \(index + 1)/\(hashes.count) · 只读")
            let target = try localURL("blobs/" + hash)
            if fm.fileExists(atPath: target.path), (try? ReadOnlySyncFiles.hash(target)) == hash { continue }
            let file = try await transport.download(hash)
            defer { try? fm.removeItem(at: file) }
            guard try ReadOnlySyncFiles.hash(file) == hash else { throw MojianError.invalid("同步文件校验失败，未改动笔记") }
            if fm.fileExists(atPath: target.path) { try fm.removeItem(at: target) }
            // URLSession's temporary directory may be on another volume; stage by copy, then rename.
            let staged = blobs.appendingPathComponent(UUID().uuidString + ".tmp")
            defer { try? fm.removeItem(at: staged) }
            try fm.copyItem(at: file, to: staged)
            try ReadOnlySyncFiles.rename(staged, to: target)
        }
        try Task.checkCancellation()
        // A cancelled/interrupted download never publishes an incoming journal or touches canonical files.
        let incoming = ReadOnlySyncIncoming(operationId: UUID().uuidString, endpoint: configuration.endpoint, manifest: remote, changes: changes)
        try ReadOnlySyncFiles.write(incoming, to: incomingURL)
        await progress("正在更新笔记 · 只读")
        return try await publish(incoming, apply: apply)
    }

    private func validate(_ incoming: ReadOnlySyncIncoming) throws {
        guard incoming.endpoint == configuration.endpoint, UUID(uuidString: incoming.operationId) != nil else {
            throw MojianError.invalid("只读同步恢复记录不属于此笔记库")
        }
        try incoming.manifest.validate(expectedWorkspace: configuration.workspaceId, previous: state?.manifest)
        for (path, change) in incoming.changes {
            guard ReadOnlySyncFiles.validPath(path), change.targetHash == incoming.manifest.files[path]?.hash,
                  change.expectedHash == nil || ReadOnlySyncFiles.validHash(change.expectedHash!) else {
                throw MojianError.invalid("只读同步恢复记录无效")
            }
        }
    }
    private func localURL(_ relative: String) throws -> URL { try ReadOnlySyncFiles.checked(".mojian-sync/macos-readonly/" + relative, root: root) }
    private struct Local: Sendable { var hash: String; var stamp: ReadOnlySyncStamp }
    private func scan() throws -> [String: Local] {
        var result = [String: Local]()
        func visit(_ relative: String) throws {
            let url = try ReadOnlySyncFiles.checked(relative, root: root), stamp = try ReadOnlySyncStamp(url)
            let hash: String
            if state?.stamps[relative] == stamp, let known = state?.manifest.files[relative]?.hash { hash = known }
            else { hash = try ReadOnlySyncFiles.hash(url) }
            result[relative] = Local(hash: hash, stamp: stamp)
        }
        try visit("library.json")
        func walk(_ relative: String, depth: Int) throws {
            guard depth < 12 else { throw MojianError.invalid("笔记文件夹层级无效") }
            let folder = try ReadOnlySyncFiles.checked(relative, root: root)
            for name in try fm.contentsOfDirectory(atPath: folder.path) {
                guard name != ".stfolder" else { throw MojianError.invalid("同步文件夹含 Syncthing 标记") }
                let path = relative + "/" + name
                let url = try ReadOnlySyncFiles.checked(path, root: root)
                let attributes = try fm.attributesOfItem(atPath: url.path)
                if attributes[.type] as? FileAttributeType == .typeDirectory {
                    try walk(path, depth: depth + 1); continue
                }
                if path.hasSuffix(".previous") || path.hasSuffix(".tmp") || path.contains("-sync-conflict-") { continue }
                guard ReadOnlySyncFiles.validPath(path) else { throw MojianError.invalid("笔记库含不支持的文件：\(path)") }
                try visit(path)
            }
        }
        let notebooks = try ReadOnlySyncFiles.checked("notebooks", root: root)
        if fm.fileExists(atPath: notebooks.path) { try walk("notebooks", depth: 0) }
        return result
    }

    private struct Prepared: Sendable {
        var path: String
        var target: URL
        var replacement: URL?
        var expectedStamp: ReadOnlySyncStamp?
    }
    private func publish(_ incoming: ReadOnlySyncIncoming, apply: Apply) async throws -> ReadOnlySyncResult {
        try validate(incoming)
        let local = try scan()
        for path in Set(local.keys).union(incoming.manifest.files.keys) where incoming.changes[path] == nil {
            guard local[path]?.hash == incoming.manifest.files[path]?.hash else {
                throw MojianError.invalid("下载期间本地笔记发生变化，恢复记录已保留：\(path)")
            }
        }
        var prepared = [Prepared]()
        var stamps = local.mapValues(\.stamp)
        for path in incoming.changes.keys.sorted(by: { a, b in a == "library.json" ? false : b == "library.json" ? true : a < b }) {
            try Task.checkCancellation()
            let change = incoming.changes[path]!, current = local[path]?.hash
            if current == change.targetHash { continue }
            guard current == change.expectedHash else { throw MojianError.invalid("本地笔记发生变化，恢复记录已保留：\(path)") }
            let target = try ReadOnlySyncFiles.checked(path, root: root)
            if let current {
                let recovery = try localURL("recovery/\(incoming.operationId)/\(path)")
                if !fm.fileExists(atPath: recovery.path) {
                    try fm.createDirectory(at: recovery.deletingLastPathComponent(), withIntermediateDirectories: true)
                    try fm.copyItem(at: target, to: recovery)
                }
                guard try ReadOnlySyncFiles.hash(recovery) == current else { throw MojianError.invalid("恢复副本校验失败，未覆盖笔记") }
            }
            var replacement: URL?
            if let hash = change.targetHash {
                let blob = try localURL("blobs/" + hash)
                guard try ReadOnlySyncFiles.hash(blob) == hash else { throw MojianError.invalid("同步恢复文件缺失或损坏") }
                let stage = try localURL("prepared/" + ReadOnlySyncFiles.digest(Data(path.utf8)))
                try fm.createDirectory(at: stage.deletingLastPathComponent(), withIntermediateDirectories: true)
                if fm.fileExists(atPath: stage.path) { try fm.removeItem(at: stage) }
                try fm.copyItem(at: blob, to: stage)
                try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
                stamps[path] = try ReadOnlySyncStamp(stage); replacement = stage
            } else { stamps.removeValue(forKey: path) }
            prepared.append(Prepared(path: path, target: target, replacement: replacement, expectedStamp: local[path]?.stamp))
        }
        let next = ReadOnlySyncState(endpoint: configuration.endpoint, manifest: incoming.manifest, stamps: stamps)
        let stateStage = try localURL("state.next.json"), stateTarget = try localURL("state.json")
        try ReadOnlySyncFiles.write(next, to: stateStage)
        try Task.checkCancellation()
        let operations = prepared, observed = local, root = root
        let action: @Sendable () throws -> Void = {
            try Task.checkCancellation()
            // Preflight the complete observed snapshot before the first canonical mutation.
            for (path, value) in observed {
                let url = try ReadOnlySyncFiles.checked(path, root: root)
                guard (try? ReadOnlySyncStamp(url)) == value.stamp else { throw MojianError.invalid("发布前笔记文件发生变化") }
            }
            for operation in operations {
                let current = FileManager.default.fileExists(atPath: operation.target.path) ? try ReadOnlySyncStamp(operation.target) : nil
                guard current == operation.expectedStamp else { throw MojianError.invalid("发布前笔记文件发生变化") }
            }
            // Once started, finish the batch even if foreground cancellation arrives during publication.
            for operation in operations {
                if let replacement = operation.replacement { try ReadOnlySyncFiles.rename(replacement, to: operation.target) }
                else { try FileManager.default.removeItem(at: operation.target) }
            }
            try ReadOnlySyncFiles.rename(stateStage, to: stateTarget)
        }
        try await apply(action)
        // A host callback that returns without executing its Action must not acknowledge the batch.
        guard !fm.fileExists(atPath: stateStage.path) else { throw MojianError.invalid("只读同步尚未完成发布") }
        state = next
        try fm.removeItem(at: incomingURL)
        for name in ["blobs", "prepared"] {
            let url = try localURL(name)
            if fm.fileExists(atPath: url.path) { try? fm.removeItem(at: url) }
        }
        return ReadOnlySyncResult(revision: incoming.manifest.revision, changed: incoming.changes.count,
                                  liveFiles: incoming.manifest.files.values.filter { $0.hash != nil }.count)
    }
}
