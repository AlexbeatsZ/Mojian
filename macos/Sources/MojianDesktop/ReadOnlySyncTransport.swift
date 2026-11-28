import Foundation
import CryptoKit
import Security
import MojianCore

struct ReadOnlySyncConfiguration: Codable, Sendable {
    var endpoint: String
    var token: String
    var enabled: Bool
    var addresses: [String] = []
    var certificatePins: [String: String] = [:]
    var workspaceId: String
    var allowInitialReplacement = false

    static func fileURL(root: URL) -> URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/Application Support/Mojian/readonly-sync")
            .appendingPathComponent(ReadOnlySyncFiles.digest(Data(root.standardizedFileURL.resolvingSymlinksInPath().path.utf8)) + ".json")
    }
    static func load(root: URL) throws -> Self? {
        let url = fileURL(root: root)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try JSONDecoder().decode(Self.self, from: Data(contentsOf: url))
    }
    func routes() throws -> [URL] {
        guard !token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !token.contains("\r"), !token.contains("\n"),
              workspaceId.range(of: "^[A-Za-z0-9_-]{16,80}\\z", options: .regularExpression) != nil else {
            throw MojianError.invalid("只读同步配置无效")
        }
        return try (addresses.isEmpty ? [endpoint] : addresses).map { address in
            guard let url = URL(string: address), let host = url.host,
                  url.scheme == "https" || (url.scheme == "http" && ["localhost", "127.0.0.1", "::1"].contains(host)),
                  url.user == nil, url.password == nil, url.query == nil, url.fragment == nil,
                  url.path.hasSuffix("/mojian-sync/v1") || url.path.hasSuffix("/mojian-sync/v1/") else {
                throw MojianError.invalid("只读同步地址必须是 HTTPS Mojian 服务")
            }
            return url.absoluteString.hasSuffix("/") ? url : URL(string: url.absoluteString + "/")!
        }
    }
}

extension ReadOnlySyncConfiguration {
    private enum CodingKeys: String, CodingKey {
        case endpoint, token, enabled, addresses, certificatePins, workspaceId, allowInitialReplacement
    }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        endpoint = try values.decode(String.self, forKey: .endpoint)
        token = try values.decode(String.self, forKey: .token)
        enabled = try values.decode(Bool.self, forKey: .enabled)
        addresses = try values.decodeIfPresent([String].self, forKey: .addresses) ?? []
        certificatePins = try values.decodeIfPresent([String: String].self, forKey: .certificatePins) ?? [:]
        workspaceId = try values.decode(String.self, forKey: .workspaceId)
        allowInitialReplacement = try values.decodeIfPresent(Bool.self, forKey: .allowInitialReplacement) ?? false
    }
}

struct ReadOnlySyncEntry: Codable, Equatable, Sendable {
    var hash: String?
    var version: Int64
    /// Folder items (file-sync-core 0.4.0) have no hash.
    var directory: Bool? = nil
    /// What the item is: a blob hash, ``ReadOnlySyncFiles/directory`` or nil for a tombstone.
    var identity: String? { directory == true ? ReadOnlySyncFiles.directory : hash }
}
struct ReadOnlySyncManifest: Codable, Equatable, Sendable {
    var workspaceId: String
    var revision: Int64
    var files: [String: ReadOnlySyncEntry]
    /// `checkpoint`: a manifest this device stored earlier; it may predate the folder library, so it need not hold folders.
    func validate(expectedWorkspace: String, previous: Self?, checkpoint: Bool = false) throws {
        guard workspaceId == expectedWorkspace, previous == nil || previous?.workspaceId == workspaceId else {
            throw MojianError.invalid("同步服务器的笔记库身份不符，已停止接收")
        }
        guard revision >= 0, revision <= 9_007_199_254_740_991,
              previous == nil || revision >= previous!.revision else {
            throw MojianError.invalid("同步服务器版本回退，已停止接收")
        }
        // Case-insensitive uniqueness holds only among live items: a case-only rename leaves the old spelling as a tombstone.
        var folded = Set<String>()
        for (path, entry) in files {
            guard ReadOnlySyncFiles.validPath(path), entry.identity == nil || folded.insert(path.lowercased()).inserted,
                  entry.version > 0, entry.version <= revision, entry.directory != true || entry.hash == nil,
                  entry.hash == nil || ReadOnlySyncFiles.validHash(entry.hash!) else {
                throw MojianError.invalid("同步清单含无效路径或版本")
            }
        }
        if let previous {
            for (path, old) in previous.files {
                guard let entry = files[path], entry.version >= old.version,
                      entry.version != old.version || entry.identity == old.identity else {
                    throw MojianError.invalid("同步清单丢失或改写已有版本")
                }
            }
            guard revision != previous.revision || files == previous.files else {
                throw MojianError.invalid("同步清单在同一版本发生变化")
            }
        }
        // A manifest without any notebook folder would empty the library.
        guard checkpoint || files.contains(where: { $0.value.directory == true && !$0.key.contains("/") && !$0.key.hasPrefix(".") }) else {
            throw MojianError.invalid("同步清单缺少笔记本文件夹")
        }
    }
}

protocol ReadOnlySyncTransport: Sendable {
    func manifest() async throws -> ReadOnlySyncManifest
    /// The returned file belongs to the caller; implementations must never load a blob into RAM.
    func download(_ hash: String) async throws -> URL
}

enum ReadOnlySyncTransportError: Error {
    case service(Int)
}

final class ReadOnlySyncHTTP: NSObject, ReadOnlySyncTransport, URLSessionDelegate, URLSessionTaskDelegate, @unchecked Sendable {
    private let base: URL
    private let token: String
    private let pin: String?
    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 900
        config.urlCache = nil
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()
    init(base: URL, configuration: ReadOnlySyncConfiguration) {
        self.base = base; token = configuration.token
        pin = configuration.certificatePins[base.absoluteString]?.lowercased().replacingOccurrences(of: ":", with: "")
    }
    func close() { session.invalidateAndCancel() }
    private func request(_ path: String, probe: Bool = false) -> URLRequest {
        var request = URLRequest(url: base.appendingPathComponent(path))
        request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        request.timeoutInterval = probe ? 8 : 60
        return request
    }
    private func validate(_ response: URLResponse) throws {
        guard let response = response as? HTTPURLResponse, response.url?.host == base.host,
              response.url?.port == base.port, response.url?.scheme == base.scheme else {
            throw MojianError.invalid("同步响应地址无效")
        }
        guard response.statusCode == 200 else { throw ReadOnlySyncTransportError.service(response.statusCode) }
    }
    func manifest() async throws -> ReadOnlySyncManifest {
        // Download even the manifest to disk, enforcing the size limit before JSON allocation.
        let (url, response) = try await session.download(for: request("manifest", probe: true))
        defer { try? FileManager.default.removeItem(at: url) }
        try validate(response)
        guard try ReadOnlySyncStamp(url).size <= 16 * 1024 * 1024 else { throw MojianError.invalid("同步清单过大") }
        return try JSONDecoder().decode(ReadOnlySyncManifest.self, from: Data(contentsOf: url))
    }
    func download(_ hash: String) async throws -> URL {
        let (url, response) = try await session.download(for: request("blobs/" + hash))
        do {
            try validate(response)
            guard try ReadOnlySyncStamp(url).size <= 1_073_741_824 else { throw MojianError.invalid("同步文件过大") }
            return url
        } catch { try? FileManager.default.removeItem(at: url); throw error }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust, let pin else {
            completionHandler(.performDefaultHandling, nil); return
        }
        guard challenge.protectionSpace.host == base.host,
              let trust = challenge.protectionSpace.serverTrust,
              let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate], let leaf = chain.first,
              ReadOnlySyncFiles.digest(SecCertificateCopyData(leaf) as Data) == pin else {
            completionHandler(.cancelAuthenticationChallenge, nil); return
        }
        completionHandler(.useCredential, URLCredential(trust: trust))
    }
}
