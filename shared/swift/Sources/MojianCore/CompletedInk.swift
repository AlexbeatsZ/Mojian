import Foundation
import CoreGraphics

public extension Settings {
    /// Live input stays native. Completed ink carries the filled geometry used at pen-up.
    func completedStroke(_ points: [InkSample], shape: Bool = false) -> JSON {
        var record = stroke(points, shape: shape)
        let path = InkPath.make(record)
        var commands = [String]()
        func point(_ value: CGPoint) -> String { "\(Double(value.x)),\(Double(value.y))" }
        path.applyWithBlock { pointer in
            let element = pointer.pointee
            switch element.type {
            case .moveToPoint: commands.append("M\(point(element.points[0]))")
            case .addLineToPoint: commands.append("L\(point(element.points[0]))")
            case .addQuadCurveToPoint: commands.append("Q\(point(element.points[0])) \(point(element.points[1]))")
            case .addCurveToPoint: commands.append("C\(point(element.points[0])) \(point(element.points[1])) \(point(element.points[2]))")
            case .closeSubpath: commands.append("Z")
            @unknown default: break
            }
        }
        let bounds = path.boundingBoxOfPath
        if !commands.isEmpty && !bounds.isNull {
            record["vectorPath"] = .string("F1 " + commands.joined(separator: " "))
            record["vectorBounds"] = .array([bounds.minX, bounds.minY, bounds.maxX, bounds.maxY].map { .number(Double($0)) })
            record["vectorTransform"] = .array([1, 0, 0, 1, 0, 0].map { .number(Double($0)) })
        }
        return record
    }
}
