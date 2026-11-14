import Foundation

/// Lossless schema wrapper: platform-only fields must survive editable exchange.
public enum JSON: Codable, Equatable, Sendable {
    case object([String: JSON]), array([JSON]), string(String), number(Double), bool(Bool), null
    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Double.self) { self = .number(v) }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode([JSON].self) { self = .array(v) }
        else { self = .object(try c.decode([String: JSON].self)) }
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let v): try c.encode(v)
        case .number(let v): try c.encode(v)
        case .string(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        case .object(let v): try c.encode(v)
        }
    }
    public subscript(_ key: String) -> JSON {
        get { if case .object(let v) = self { return v[key] ?? .null }; return .null }
        set { var v = dictionary; v[key] = newValue; self = .object(v) }
    }
    public var dictionary: [String: JSON] { if case .object(let v) = self { return v }; return [:] }
    public var array: [JSON] { if case .array(let v) = self { return v }; return [] }
    public var string: String { if case .string(let v) = self { return v }; return "" }
    public func number(_ fallback: Double = 0) -> Double {
        if case .number(let v) = self, v.isFinite { return v }; return fallback
    }
    public func bool(_ fallback: Bool = false) -> Bool { if case .bool(let v) = self { return v }; return fallback }
    public func data() throws -> Data {
        let e = JSONEncoder(); e.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]; return try e.encode(self)
    }
    public static func read(_ data: Data) throws -> JSON { try JSONDecoder().decode(JSON.self, from: data) }
    public static func id(_ prefix: String) -> String { "\(prefix)-\(UUID().uuidString.lowercased())" }
}

public enum MojianError: Error, LocalizedError {
    case invalid(String)
    public var errorDescription: String? { if case .invalid(let s) = self { return s }; return nil }
}
