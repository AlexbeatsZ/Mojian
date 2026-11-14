import Foundation
import MojianCore

/// One ribbon tool item. Presets are add-only and device-local (`workspace-ui.md`, drawing-tool contract).
struct ToolPreset: Codable, Equatable {
    var id: String
    var tool: PenTool
    var color = 0
    /// Pen/highlighter/shape width, or the eraser radius, in millimetres.
    var widthMM = 0.4
    var opacity = 32.0
    var shape: ShapeKind = .line

    static let defaults = [
        ToolPreset(id: "lasso", tool: .lasso),
        ToolPreset(id: "eraser", tool: .eraser, widthMM: 4.5),
        ToolPreset(id: "highlighter", tool: .highlighter, color: 1, widthMM: 5.5),
        ToolPreset(id: "pen-black", tool: .pen),
        ToolPreset(id: "pen-blue", tool: .pen, color: 1),
        ToolPreset(id: "pen-red", tool: .pen, color: 2),
        ToolPreset(id: "shape", tool: .shape),
        ToolPreset(id: "hand", tool: .hand)
    ]
    static func added(_ tool: PenTool) -> ToolPreset {
        ToolPreset(id: "\(tool.rawValue)-\(UUID().uuidString.prefix(8).lowercased())", tool: tool, widthMM: tool == .highlighter ? 5.5 : 0.4)
    }
    var icon: String {
        switch tool {
        case .pen: return "pen"; case .highlighter: return "highlight"; case .eraser: return "eraser"
        case .lasso: return "lasso"; case .hand: return "hand_left"; case .shape: return "shapes"
        }
    }
    var hasFlyout: Bool { [.pen, .highlighter, .eraser, .shape].contains(tool) }
    var title: String {
        let color = ["黑色", "蓝色", "红色"][min(2, max(0, self.color))]
        switch tool {
        case .pen: return color + "钢笔"
        case .highlighter: return color + "荧光笔"
        case .shape: return "图形（\(shape.title)）"
        case .hand: return "触控笔抓手"
        default: return tool.title
        }
    }
    var widthRange: ClosedRange<Double> {
        switch tool { case .eraser: return 0.25...40; case .highlighter: return 1...12; default: return 0.1...2 }
    }
    /// Copies the preset into the shared drawing settings consumed by the canvas and stroke factory.
    func apply(to settings: inout Settings) {
        settings.tool = tool
        switch tool {
        case .pen: settings.color = color; settings.widthMM = widthMM
        case .highlighter: settings.color = color; settings.highlighterWidthMM = widthMM; settings.highlighterOpacity = opacity
        case .eraser: settings.eraserRadiusMM = widthMM
        case .shape: settings.color = color; settings.widthMM = widthMM; settings.shape = shape
        case .lasso, .hand: break
        }
    }
}
