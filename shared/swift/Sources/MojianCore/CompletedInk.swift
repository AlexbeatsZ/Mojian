import Foundation
import CoreGraphics

public extension Settings {
    /// Live input stays native. Completed ink carries the filled geometry used at pen-up.
    func completedStroke(_ points: [InkSample], shape: Bool = false) -> JSON {
        var record = stroke(points, shape: shape)
        let path = InkPath.make(record)
        let text = InkPath.compactText(path)
        let bounds = path.boundingBoxOfPath
        if !text.isEmpty && !bounds.isNull {
            record["vectorPath"] = .string("F1 " + text)
            record["vectorBounds"] = .array([bounds.minX, bounds.minY, bounds.maxX, bounds.maxY].map { .number(Double($0)) })
            record["vectorTransform"] = .array([1, 0, 0, 1, 0, 0].map { .number(Double($0)) })
        }
        return record
    }
}

public extension InkPath {
    /// Shared compact outline text (same form as Windows 0.7.34): points on a 0.01-unit grid,
    /// absolute `M` per figure and relative `l`/`q`/`c`, without repeated identical figures.
    /// Full-precision per-segment discs made dense pages hundreds of MB, beyond Android's heap.
    static func compactText(_ path: CGPath) -> String {
        struct Figure { var start: CGPoint; var parts: [(Character, [CGPoint])] = []; var closed = false }
        var figures = [Figure]()
        var current: Figure?
        path.applyWithBlock { pointer in
            let element = pointer.pointee
            switch element.type {
            case .moveToPoint:
                if let figure = current { figures.append(figure) }
                current = Figure(start: element.points[0])
            case .addLineToPoint: current?.parts.append(("l", [element.points[0]]))
            case .addQuadCurveToPoint: current?.parts.append(("q", [element.points[0], element.points[1]]))
            case .addCurveToPoint: current?.parts.append(("c", [element.points[0], element.points[1], element.points[2]]))
            case .closeSubpath:
                if var figure = current { figure.closed = true; figures.append(figure) }
                current = nil
            @unknown default: break
            }
        }
        if let figure = current { figures.append(figure) }
        func grid(_ value: CGFloat) -> Double { (Double(value) * 100).rounded() / 100 }
        func number(_ value: Double) -> String {
            let rounded = (value * 100).rounded() / 100
            if rounded == 0 { return "0" }
            var text = String(format: "%.2f", rounded)
            while text.hasSuffix("0") { text.removeLast() }
            if text.hasSuffix(".") { text.removeLast() }
            if text.hasPrefix("0.") { text.removeFirst() } else if text.hasPrefix("-0.") { text.remove(at: text.index(after: text.startIndex)) }
            return text
        }
        var seen = Set<String>()
        var output = [String]()
        for figure in figures where !figure.parts.isEmpty {
            var origin = (grid(figure.start.x), grid(figure.start.y))
            var text = "M\(number(origin.0)),\(number(origin.1))"
            for (command, points) in figure.parts {
                // Every control point of a segment is relative to that segment's start point.
                let rounded = points.map { (grid($0.x), grid($0.y)) }
                text += String(command) + rounded.map { "\(number($0.0 - origin.0)),\(number($0.1 - origin.1))" }.joined(separator: " ")
                origin = rounded[rounded.count - 1]
            }
            if figure.closed { text += "z" }
            if seen.insert(text).inserted { output.append(text) }
        }
        return output.joined()
    }
}
