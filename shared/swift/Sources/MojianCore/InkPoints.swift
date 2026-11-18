import Foundation

/// Shared packed stroke samples (`pointsCoded`, shared/ink-points.md): per-channel zigzag LEB128
/// deltas of quantized integers. Pages are expanded to `points` arrays when loaded and packed again
/// when written, so the rest of the macOS client keeps working with point objects.
public enum InkPoints {
    public static let prefix = "1:"
    static let names = ["x", "y", "pressure", "elapsedMilliseconds", "tiltX", "tiltY", "tiltRadians", "orientationRadians"]
    static let scales: [Double] = [1000, 1000, 10000, 1, 100, 100, 10000, 10000]

    static func quantize(_ value: Double, _ scale: Double) -> Int64 {
        let scaled = value * scale
        return scaled >= 0 ? Int64((scaled + 0.5).rounded(.down)) : -Int64((-scaled + 0.5).rounded(.down))
    }

    /// Nil when a point has other fields, mixed optional channels or non-finite values.
    public static func encode(_ points: [JSON]) -> String? {
        guard !points.isEmpty else { return nil }
        var rows = [[Double]](), mask = -1
        for point in points {
            guard case .object(let fields) = point else { return nil }
            var row = [Double](repeating: 0, count: names.count), present = 0
            for (key, value) in fields {
                guard let channel = names.firstIndex(of: key), case .number(let number) = value, number.isFinite else { return nil }
                if channel == 3 && number != number.rounded(.down) { return nil }
                row[channel] = number; present |= 1 << channel
            }
            guard present & 3 == 3, mask < 0 || present == mask else { return nil }
            mask = present; rows.append(row)
        }
        var bytes = [UInt8]()
        func unsigned(_ value: UInt64) {
            var v = value
            while v >= 0x80 { bytes.append(UInt8(v & 0x7f) | 0x80); v >>= 7 }
            bytes.append(UInt8(v))
        }
        unsigned(UInt64(rows.count)); unsigned(UInt64(mask >> 2))
        for channel in names.indices where mask & (1 << channel) != 0 {
            var previous: Int64 = 0
            for row in rows {
                let q = quantize(row[channel], scales[channel])
                let delta = q &- previous; previous = q
                unsigned(UInt64(bitPattern: (delta << 1) ^ (delta >> 63)))
            }
        }
        return prefix + Data(bytes).base64EncodedString()
    }

    public static func decode(_ text: String) throws -> [JSON] {
        guard text.hasPrefix(prefix), let data = Data(base64Encoded: String(text.dropFirst(prefix.count))) else {
            throw MojianError.invalid("笔迹采样编码无效")
        }
        let bytes = [UInt8](data)
        var offset = 0
        func unsigned() throws -> UInt64 {
            var result: UInt64 = 0, shift: UInt64 = 0
            while true {
                guard offset < bytes.count, shift <= 63 else { throw MojianError.invalid("笔迹采样编码已损坏") }
                let b = bytes[offset]; offset += 1
                result |= UInt64(b & 0x7f) << shift
                if b & 0x80 == 0 { return result }
                shift += 7
            }
        }
        let rawCount = try unsigned()
        guard rawCount <= UInt64(Int32.max) else { throw MojianError.invalid("笔迹采样编码已损坏") }
        let count = Int(rawCount)
        let mask = (Int(try unsigned()) << 2) | 3
        var columns = [Int: [Double]]()
        for channel in names.indices where mask & (1 << channel) != 0 {
            var column = [Double](), value: Int64 = 0
            column.reserveCapacity(count)
            for _ in 0..<count {
                let z = try unsigned()
                value = value &+ (Int64(bitPattern: z >> 1) ^ -Int64(bitPattern: z & 1))
                column.append(Double(value) / scales[channel])
            }
            columns[channel] = column
        }
        guard offset == bytes.count else { throw MojianError.invalid("笔迹采样编码已损坏") }
        return (0..<count).map { i in
            .object(Dictionary(uniqueKeysWithValues: columns.map { (names[$0.key], JSON.number($0.value[i])) }))
        }
    }

    /// Replaces each stroke's `pointsCoded` by a `points` array (for in-memory editing).
    public static func expand(_ page: JSON) throws -> JSON {
        guard case .array(let strokes) = page["strokes"] else { return page }
        var result = page
        result["strokes"] = .array(try strokes.map { stroke in
            guard case .string(let coded) = stroke["pointsCoded"], stroke["points"] == .null else { return stroke }
            var fields = stroke.dictionary
            fields.removeValue(forKey: "pointsCoded")
            fields["points"] = .array(try decode(coded))
            return .object(fields)
        })
        return result
    }

    /// Packs every eligible `points` array as `pointsCoded` (what is written to disk).
    public static func pack(_ page: JSON) -> JSON {
        guard case .array(let strokes) = page["strokes"] else { return page }
        var result = page
        result["strokes"] = .array(strokes.map { stroke in
            guard case .array(let points) = stroke["points"], let coded = encode(points) else { return stroke }
            var fields = stroke.dictionary
            fields.removeValue(forKey: "points")
            fields["pointsCoded"] = .string(coded)
            return .object(fields)
        })
        return result
    }
}
