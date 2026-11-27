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
    static func validPath(_ path: String) -> Bool {
        guard path.utf8.count < 500, !path.contains("\\"),
              !path.contains("-sync-conflict-"), !path.hasSuffix(".previous"), !path.hasSuffix(".tmp") else { return false }
        if path == "library.json" { return true }
        let parts = path.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard (parts.count == 4 && parts[0] == "notebooks" && parts[2] == "pages" && parts[3].hasSuffix(".json")) ||
                (parts.count == 5 && parts[0] == "notebooks" && parts[2] == "assets") else { return false }
        return parts.allSatisfy { component in
            let stem = component.split(separator: ".", omittingEmptySubsequences: false).first?.uppercased() ?? ""
            return component.range(of: "^[A-Za-z0-9_-][A-Za-z0-9_.-]*\\z", options: .regularExpression) != nil &&
                !component.hasSuffix(".") && !["CON", "PRN", "AUX", "NUL"].contains(stem) &&
                stem.range(of: "^(COM|LPT)[1-9]$", options: .regularExpression) == nil
        }
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
