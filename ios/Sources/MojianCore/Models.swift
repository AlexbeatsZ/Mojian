import Foundation

public let unitsPerMM = 96.0 / 25.4
public let androidUnitsPerUnit = 12.16225 / unitsPerMM

public struct InkSample: Equatable, Sendable {
    public var x: Double, y: Double, pressure: Double, elapsed: Double, tiltX: Double, tiltY: Double
    public init(x: Double, y: Double, pressure: Double = 0.5, elapsed: Double = 0, tiltX: Double = 0, tiltY: Double = 0) {
        self.x = x; self.y = y; self.pressure = min(1, max(0.01, pressure)); self.elapsed = elapsed
        self.tiltX = tiltX; self.tiltY = tiltY
    }
    public init(_ json: JSON) {
        self.init(x: json["x"].number(), y: json["y"].number(), pressure: json["pressure"].number(0.5),
                  elapsed: json["elapsedMilliseconds"].number(), tiltX: json["tiltX"].number(), tiltY: json["tiltY"].number())
    }
    public var point: CGPoint { CGPoint(x: x, y: y) }
    public var json: JSON { .object(["x": .number(x), "y": .number(y), "pressure": .number(pressure),
        "elapsedMilliseconds": .number(elapsed.rounded()), "tiltX": .number(tiltX), "tiltY": .number(tiltY)]) }
}

public enum PenTool: String, Codable, CaseIterable, Sendable {
    case pen, highlighter, eraser, lasso, hand, shape
    public var title: String {
        switch self { case .pen: return "钢笔"; case .highlighter: return "荧光笔"; case .eraser: return "橡皮"
        case .lasso: return "套索"; case .hand: return "抓手"; case .shape: return "图形" }
    }
    public var symbol: String {
        switch self { case .pen: return "pencil.tip"; case .highlighter: return "highlighter"; case .eraser: return "eraser"
        case .lasso: return "lasso"; case .hand: return "hand.draw"; case .shape: return "square.on.circle" }
    }
}
public enum PencilAction: String, Codable, CaseIterable, Sendable {
    case system, none, eraser, previousTool, pen, highlighter, lasso, hand, undo, redo, palette, fullPage
    public var title: String {
        switch self { case .system: return "跟随系统"; case .none: return "无操作"; case .eraser: return "切换橡皮"
        case .previousTool: return "上一个工具"; case .pen: return "钢笔"; case .highlighter: return "荧光笔"
        case .lasso: return "套索"; case .hand: return "抓手"; case .undo: return "撤销"; case .redo: return "重做"
        case .palette: return "工具设置"; case .fullPage: return "切换全页" }
    }
}
public struct Settings: Codable, Equatable, Sendable {
    public var tool: PenTool = .pen
    public var color = 0
    public var widthMM = 0.4
    public var pressurePercent = 200.0
    public var flowPercent = 100.0
    public var highlighterWidthMM = 5.5
    public var highlighterOpacity = 32.0
    public var eraserRadiusMM = 4.5
    public var pressureErase = true
    public var pressureThreshold = 0.8
    public var dwellMS = 150.0
    public var travelMM = 1.0
    public var straighten = true
    public var straightenMS = 1000.0
    public var fingerWriting = false
    public var prediction = true
    public var pinchLocked = false
    public var handSensitivity = 1.0
    public var recursivePages = false
    public var doubleTap: PencilAction = .system
    public var squeeze: PencilAction = .system
    public var penKeyOne = ""
    public var penKeyTwo = ""
    public var penKeyOneAction: PencilAction = .eraser
    public var penKeyTwoAction: PencilAction = .undo
    public var shape: ShapeKind = .line
    public init() {}
    public func stroke(_ points: [InkSample], shape: Bool = false) -> JSON {
        .object(["id": .string(JSON.id("stroke")), "kind": .number(tool == .highlighter ? 1 : 0),
            "color": .number(Double(color)), "widthMm": .number(tool == .highlighter ? highlighterWidthMM : widthMM),
            "pressurePercent": .number(pressurePercent), "inkFlowPercent": .number(flowPercent),
            "opacityPercent": .number(tool == .highlighter ? highlighterOpacity : 100),
            "brushProfile": .string(shape ? "shape-v1" : "fountain-v1"), "points": .array(points.map(\.json))])
    }
}

public struct PageDocument: Equatable, Sendable {
    public var json: JSON
    public init(notebook: String, page: String) {
        json = .object(["version": .number(2), "notebookId": .string(notebook), "pageId": .string(page),
            "strokes": .array([]), "objects": .array([]), "tags": .array([]), "textItems": .array([]), "layers": .array([])])
    }
    public init(_ json: JSON) throws {
        guard !json["pageId"].string.isEmpty, case .object = json,
              (1...2).contains(Int(json["version"].number(1))) else { throw MojianError.invalid("页面格式无效或版本不受支持") }
        for key in ["strokes","objects","tags","textItems","layers"] where json[key] != .null {
            guard case .array(let items)=json[key],items.allSatisfy({if case .object=$0{return true};return false}) else {throw MojianError.invalid("页面内容格式无效：\(key)")}
        }
        for stroke in json["strokes"].array where stroke["points"] != .null {
            guard case .array(let points)=stroke["points"],points.allSatisfy({if case .object=$0{return true};return false}) else {throw MojianError.invalid("笔迹采样格式无效")}
        }
        self.json = json
    }
    public var notebookID: String { json["notebookId"].string }
    public var pageID: String { json["pageId"].string }
    public var strokes: [JSON] { get { json["strokes"].array } set { json["strokes"] = .array(newValue) } }
    public var objects: [JSON] { get { json["objects"].array } set { json["objects"] = .array(newValue) } }
    public var texts: [JSON] { get { json["textItems"].array } set { json["textItems"] = .array(newValue) } }
    public var tags: [JSON] { get { json["tags"].array } set { json["tags"] = .array(newValue) } }
    public var contentBounds: CGRect {
        var result = CGRect.null
        for key in ["strokes", "objects", "textItems", "tags"] {
            for item in json[key].array { result = result.union(Geometry.bounds(item, category: key)) }
        }
        return result
    }
    public mutating func transform(ids: Set<String>, from: CGRect, to: CGRect) {
        let sx = to.width / max(0.001, from.width), sy = to.height / max(0.001, from.height)
        for key in ["strokes", "objects", "textItems", "tags"] {
            json[key] = .array(json[key].array.map { item in
                guard ids.contains(item["id"].string) else { return item }
                var v = item
                if key == "strokes" {
                    v["iosGeometryRevision"] = .number(item["iosGeometryRevision"].number()+1)
                    v["points"] = .array(item["points"].array.map { p in
                        var p = p; p["x"] = .number(to.minX + (p["x"].number() - from.minX) * sx)
                        p["y"] = .number(to.minY + (p["y"].number() - from.minY) * sy); return p
                    })
                    v["widthMm"] = .number(item["widthMm"].number(0.4) * sqrt(sx * sy))
                    if !item["vectorPath"].string.isEmpty {
                        let m = item["vectorTransform"].array.map { $0.number() }
                        let a = m.count == 6 ? m : [1,0,0,1,0,0]
                        v["vectorTransform"] = .array([a[0]*sx,a[1]*sy,a[2]*sx,a[3]*sy,
                            to.minX + (a[4]-from.minX)*sx, to.minY + (a[5]-from.minY)*sy].map(JSON.number))
                    }
                } else {
                    let x = key == "tags" ? "documentX" : "x", y = key == "tags" ? "documentY" : "y"
                    v[x] = .number(to.minX + (item[x].number()-from.minX)*sx)
                    v[y] = .number(to.minY + (item[y].number()-from.minY)*sy)
                    if key == "tags" { v["scale"] = .number(item["scale"].number(1)*sqrt(sx*sy)) }
                    else { v["width"] = .number(item["width"].number(240)*sx); v["height"] = .number(item["height"].number(60)*sy) }
                }
                return v
            })
        }
    }
    public mutating func delete(ids: Set<String>) {
        for key in ["strokes", "objects", "textItems", "tags"] {
            json[key] = .array(json[key].array.filter { !ids.contains($0["id"].string) })
        }
    }
}

public enum Background: String, CaseIterable {
    case blank = "BLANK", ruledNarrow = "RULE_NARROW", ruled = "RULE_STANDARD", ruledWide = "RULE_WIDE", ruledClean = "RULE_CLEAN"
    case twoColumn = "RULE_TWO_COLUMN_STANDARD", twoColumnNarrow = "RULE_TWO_COLUMN_NARROW", twoColumnWide = "RULE_TWO_COLUMN_WIDE"
    case gridDense = "GRID_DENSE", grid = "GRID_STANDARD", gridWide = "GRID_WIDE"
    case dotsDense = "DOTS_DENSE", dots = "DOTS_STANDARD", dotsWide = "DOTS_WIDE"
    public var title: String {
        switch self { case .blank: return "空白"; case .ruledNarrow: return "窄横线"; case .ruled: return "标准横线"
        case .ruledWide: return "宽横线"; case .ruledClean: return "纯横线（无边线）"; case .twoColumn: return "标准双栏横线"; case .twoColumnNarrow: return "窄双栏横线"
        case .twoColumnWide: return "宽双栏横线"; case .gridDense: return "密集网格"; case .grid: return "标准网格"
        case .gridWide: return "宽网格"; case .dotsDense: return "密集点阵"; case .dots: return "标准点阵"; case .dotsWide: return "宽点阵" }
    }
    public static func restore(_ raw: String) -> Background? {
        Background(rawValue:raw.replacingOccurrences(of:"RULED_",with:"RULE_"))
    }
    public var showsMargin: Bool {rawValue.hasPrefix("RULE_") && self != .ruledClean}
    public var spacing: Double {
        let factor = rawValue.contains("NARROW") ? 0.75 : rawValue.contains("DENSE") ? 0.5 : rawValue.contains("WIDE") ? (rawValue.hasPrefix("RULE_") ? 1.35 : 1.5) : 1
        return 28*factor
    }
}

public final class Page {
    public var json: JSON
    public init(_ json: JSON) { self.json = json }
    public convenience init(title: String = "无标题页") {
        self.init(.object(["id": .string(JSON.id("page")), "title": .string(title), "kind": .string("PAGE"), "viewSettings": Self.defaultView]))
    }
    public static var defaultView: JSON { .object(["paperSize": .string("BOUNDLESS"), "pageColor": .string("WHITE"),
        "backgroundFormat": .string("GRID_STANDARD"), "darkBackground": .bool(true), "pageTitleVisible": .bool(true),
        "a4PageCount": .number(1), "boundlessDocumentWidth": .number(210*12.16225), "boundlessDocumentHeight": .number(297*12.16225)]) }
    public var id: String { json["id"].string }
    public var title: String { get { json["title"].string } set { json["title"] = .string(newValue) } }
    public var view: JSON { get { json["viewSettings"] == .null ? Self.defaultView : json["viewSettings"] } set { json["viewSettings"] = newValue } }
    public var dark: Bool { view["darkBackground"].bool(true) }
    public var paper: String { view["paperSize"].string }
    public var width: Double { paper == "BOUNDLESS" ? view["boundlessDocumentWidth"].number(210*12.16225)/androidUnitsPerUnit : view["customDocumentWidth"].number(210*unitsPerMM) }
    public var height: Double { paper == "BOUNDLESS" ? view["boundlessDocumentHeight"].number(297*12.16225)/androidUnitsPerUnit : view["customDocumentHeight"].number(297*unitsPerMM) }
    public var sheetCount: Int { min(10000, max(1, Int(view["a4PageCount"].number(1)))) }
    public func grow(content: CGRect, visibleBottom: Double) -> Bool {
        var v = view
        if paper == "A4" {
            let observed = max(content.isNull ? 0 : content.maxY, visibleBottom), margin = 24*unitsPerMM, gap = 12*unitsPerMM
            let count = min(10000, max(sheetCount, Int(max(0, observed+margin+gap)/(height+gap))+1))
            v["a4PageCount"] = .number(Double(count))
        } else if paper == "BOUNDLESS", !content.isNull {
            v["boundlessDocumentWidth"] = .number(max(width, content.maxX+24*unitsPerMM)*androidUnitsPerUnit)
            v["boundlessDocumentHeight"] = .number(max(height, content.maxY+24*unitsPerMM)*androidUnitsPerUnit)
        }
        guard v != view else { return false }; view = v; return true
    }
}
public final class Section {
    public var id: String, title: String, expanded: Bool, pages: [Page], children: [Section]
    public init(id: String = JSON.id("section"), title: String = "新分区", pages: [Page] = [], children: [Section] = [], expanded: Bool = true) {
        self.id = id; self.title = title; self.pages = pages; self.children = children; self.expanded = expanded
    }
    public convenience init(_ j: JSON) {
        self.init(id: j["id"].string, title: j["title"].string, pages: j["pages"].array.map(Page.init), children: j["children"].array.map(Section.init), expanded: j["expanded"].bool(true))
    }
    public var json: JSON { .object(["id": .string(id), "title": .string(title), "expanded": .bool(expanded), "pages": .array(pages.map(\.json)), "children": .array(children.map(\.json))]) }
    public var all: [Section] { [self] + children.flatMap(\.all) }
}
public final class Notebook {
    public var id: String, title: String, sections: [Section]
    public init(id: String = JSON.id("notebook"), title: String = "我的笔记本", sections: [Section] = []) { self.id = id; self.title = title; self.sections = sections }
    public convenience init(_ j: JSON) { self.init(id: j["id"].string, title: j["title"].string, sections: j["sections"].array.map(Section.init)) }
    public var allSections: [Section] { sections.flatMap(\.all) }
    public var pages: [Page] { allSections.flatMap(\.pages) }
    public var json: JSON { .object(["id": .string(id), "title": .string(title), "sections": .array(sections.map(\.json))]) }
}
