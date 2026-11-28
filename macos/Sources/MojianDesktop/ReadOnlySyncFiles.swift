import Foundation
import CryptoKit
import Darwin
import MojianCore

struct ReadOnlySyncStamp: Codable, Equatable, Sendable {
    var size: UInt64
    var modified: Date
    var inode: UInt64
    init(_ url: URL) throws {
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        guard attributes[.type] as? FileAttributeType == .typeRegular else { throw MojianError.invalid("笔记库文件类型无效") }
        size = (attributes[.size] as? NSNumber)?.uint64Value ?? 0
        modified = attributes[.modificationDate] as? Date ?? .distantPast
        inode = (attributes[.systemFileNumber] as? NSNumber)?.uint64Value ?? 0
    }
}

enum ReadOnlySyncFiles {
    static func validHash(_ hash: String) -> Bool { hash.range(of: "^[0-9a-f]{64}\\z", options: .regularExpression) != nil }
    static func digest(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    static func hash(_ url: URL) throws -> String {
        let before = try ReadOnlySyncStamp(url), handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var digest = SHA256()
        while let bytes = try handle.read(upToCount: 1_048_576), !bytes.isEmpty { digest.update(data: bytes) }
        guard try ReadOnlySyncStamp(url) == before else { throw MojianError.invalid("读取时文件发生变化，已停止接收") }
        return digest.finalize().map { String(format: "%02x", $0) }.joined()
    }
    /// A folder's identity inside the receiver; never a blob.
    static let directory = "directory"
    private static func storableName(_ part: String) -> Bool {
        !part.isEmpty && part.utf16.count <= 240 && part != "." && part != ".." &&
            !part.unicodeScalars.contains { $0.value < 0x20 || $0.value == 0x7f || "\\:*?\"<>|".unicodeScalars.contains($0) } &&
            !part.hasSuffix(".") && !part.hasSuffix(" ") &&
            part.range(of: "^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\.|$)", options: [.regularExpression, .caseInsensitive]) == nil
    }
    /// v2 names: no client creates them, but their tombstones stay in the manifest and still delete old local files.
    static func legacyPath(_ path: String) -> Bool {
        path.range(of: "^(library\\.json|notebooks/[A-Za-z0-9_-][A-Za-z0-9._-]*/(pages/[A-Za-z0-9_-][A-Za-z0-9._-]*\\.json|assets/[A-Za-z0-9_-][A-Za-z0-9._-]*/[A-Za-z0-9_-][A-Za-z0-9._-]*))\\z",
                   options: .regularExpression) != nil
    }
    /// The folder-library v3 path policy, identical to sync-server/protocol.mjs.
    static func validPath(_ path: String) -> Bool {
        guard !path.isEmpty, path.utf16.count < 1000, !path.contains("\r"), !path.contains("\n"), !path.hasSuffix(".previous"),
              !path.hasSuffix(".tmp"), !path.contains(".tmp-"), !path.contains("-sync-conflict-") else { return false }
        let parts = path.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard parts.allSatisfy(storableName) else { return false }
        if legacyPath(path) { return true }
        if parts[0] == ".mojian" { return parts.count <= 4 && (parts.count < 2 || parts[1] == "assets") }
        return parts.allSatisfy { !$0.hasPrefix(".") || $0 == FolderNames.descriptor }
    }
    /// Files that may exist on the server: pages, folder descriptors and page assets (and the v2 names being retired).
    static func isLibraryFile(_ path: String) -> Bool {
        path.hasSuffix(FolderNames.pageExtension) || path == FolderNames.descriptor || path.hasSuffix("/" + FolderNames.descriptor) ||
            (path.hasPrefix(".mojian/assets/") && path.split(separator: "/").count == 4) || legacyPath(path)
    }
    /// Device-local or temporary names that never synchronize and are not reported.
    static func ignored(_ path: String) -> Bool {
        let parts = path.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        if parts[0] == ".mojian" && (parts.count < 2 || parts[1] != "assets") { return parts.count > 1 }
        return parts.contains { part in
            [".mojian-sync", "_migration", "_backup", "_backups", ".recovery", ".DS_Store", "desktop.ini", "Thumbs.db"].contains(part) ||
                (part.hasPrefix(".") && part != FolderNames.descriptor && part != ".mojian") ||
                part.hasSuffix(".previous") || part.hasSuffix(".tmp") || part.contains(".tmp-") || part.contains("-sync-conflict-")
        }
    }
    /// Why a local item that is neither ignored nor valid cannot be received over, or nil when it can.
    static func failure(_ path: String, directory: Bool) -> String? {
        if ignored(path) { return nil }
        if !validPath(path) { return "名称含有 \\ : * ? \" < > | 或以空格、句点结尾，其他设备无法保存" }
        if !directory && !isLibraryFile(path) { return "不是墨笺页面或附件，未同步" }
        return nil
    }
    static func checked(_ relative: String, root: URL) throws -> URL {
        guard !relative.isEmpty, !relative.hasPrefix("/"), !relative.contains("\\") else { throw MojianError.invalid("同步路径无效") }
        var result = root
        for part in relative.split(separator: "/", omittingEmptySubsequences: false) {
            guard !part.isEmpty, part != ".", part != ".." else { throw MojianError.invalid("同步路径无效") }
            result.appendPathComponent(String(part))
            if let attributes = try? FileManager.default.attributesOfItem(atPath: result.path),
               attributes[.type] as? FileAttributeType == .typeSymbolicLink { throw MojianError.invalid("同步路径不允许符号链接") }
        }
        return result
    }
    /// True only for the exact spelling of the last component (APFS is usually case-insensitive).
    static func exists(_ url: URL) -> Bool {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: url.deletingLastPathComponent().path)) ?? []
        return names.contains(url.lastPathComponent)
    }
    /// Removes folders the server does not list once nothing synchronized remains in them, deepest first
    /// (the v2 `notebooks/…` tree after its files are deleted, a notebook deleted on another device).
    /// A folder that still holds a file is kept.
    static func pruneFolders(root: URL, keep: Set<String>) throws {
        let fm = FileManager.default
        func visit(_ relative: String) throws -> Bool {
            let folder = relative.isEmpty ? root : try checked(relative, root: root)
            var empty = true
            for name in try fm.contentsOfDirectory(atPath: folder.path) {
                let path = relative.isEmpty ? name : relative + "/" + name
                let url = folder.appendingPathComponent(name)
                if ignored(path) {
                    if [".DS_Store", "Thumbs.db", "desktop.ini"].contains(name) { continue }
                    empty = false; continue
                }
                let type = try fm.attributesOfItem(atPath: url.path)[.type] as? FileAttributeType
                if type == .typeDirectory, try visit(path), !keep.contains(path) {
                    try fm.removeItem(at: url); continue
                }
                empty = false
            }
            return empty
        }
        _ = try visit("")
    }
    static func atomic(_ data: Data, to url: URL) throws {
        let fm = FileManager.default
        try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let temp = url.deletingLastPathComponent().appendingPathComponent(".readonly-\(UUID().uuidString).tmp")
        defer { try? fm.removeItem(at: temp) }
        guard fm.createFile(atPath: temp.path, contents: nil, attributes: [.posixPermissions: 0o600]) else { throw MojianError.invalid("无法写入同步状态") }
        let handle = try FileHandle(forWritingTo: temp)
        do { try handle.write(contentsOf: data); try handle.synchronize(); try handle.close() }
        catch { try? handle.close(); throw error }
        try rename(temp, to: url)
    }
    static func rename(_ source: URL, to target: URL) throws {
        guard Darwin.rename(source.path, target.path) == 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
    }
    static func write<T: Encodable>(_ value: T, to url: URL) throws { try atomic(JSONEncoder().encode(value), to: url) }
}
