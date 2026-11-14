import AppKit
import MojianCore

extension NSColor {
    convenience init(hex: String) {
        let h = hex.trimmingCharacters(in: CharacterSet(charactersIn: "#")), n = UInt64(h, radix: 16) ?? 0
        self.init(srgbRed: CGFloat((n >> 16) & 255) / 255, green: CGFloat((n >> 8) & 255) / 255, blue: CGFloat(n & 255) / 255, alpha: h.count == 8 ? CGFloat((n >> 24) & 255) / 255 : 1)
    }
    var neutralLight: Bool { guard let c = usingColorSpace(.sRGB) else { return false }; return min(c.redComponent, c.greenComponent, c.blueComponent) > 0.82 }
    var neutralDark: Bool { guard let c = usingColorSpace(.sRGB) else { return false }; return max(c.redComponent, c.greenComponent, c.blueComponent) < 0.3 }
}

final class MacRenderer {
    let store: CanonicalStore
    let catalog: JSON
    private var paths = [String: CGPath]()
    private var scene = [(String, JSON, CGRect)]()
    private var images = [String: NSImage]()
    private var pdfs = [String: CGPDFDocument]()
    private var contentBounds = CGRect.null
    init(store: CanonicalStore) {
        self.store = store
        let url = Bundle.module.url(forResource: "page-backgrounds", withExtension: "json")!
        catalog = (try? JSON.read(Data(contentsOf: url))) ?? .object([:])
    }
    func bind(_ document: PageDocument) {
        paths.removeAll(keepingCapacity: true); images.removeAll(); pdfs.removeAll()
        let layers = document.json["layers"].array
        let items = document.objects.map { ("objects", $0) } + document.strokes.map { ("strokes", $0) } + document.texts.map { ("textItems", $0) } + document.tags.map { ("tags", $0) }
        func order(_ j: JSON) -> Double {
            if j["isPageBackground"].bool() { return -.greatestFiniteMagnitude }
            return layers.first { $0["id"] == j["layerId"] }?["order"].number() ?? .greatestFiniteMagnitude
        }
        scene = items.enumerated().filter { pair in
            layers.first { $0["id"] == pair.element.1["layerId"] }?["visible"].bool(true) ?? true
        }.sorted {
            let a = order($0.element.1), b = order($1.element.1)
            if a != b { return a < b }
            let x = $0.element.1["zOrder"].number(), y = $1.element.1["zOrder"].number()
            return x == y ? $0.offset < $1.offset : x < y
        }.map { _, item in
            let (category, value) = item
            let bounds: CGRect
            if category == "strokes" {
                let path = InkPath.make(value); paths[value["id"].string] = path; bounds = path.boundingBoxOfPath
            } else { bounds = Geometry.bounds(value, category: category) }
            return (category, value, bounds)
        }
        contentBounds = scene.reduce(CGRect.null) { $0.union($1.2) }
    }
    func paperBounds(_ page: Page) -> CGRect {
        if page.paper == "PDF" { return CGRect(x: 0, y: 0, width: page.width, height: page.height) }
        let b = CGRect(x: 0, y: 0, width: page.width, height: page.height)
        if page.id.hasPrefix("page-notein-") || page.id.hasPrefix("page-onenote-") {
            return contentBounds.isNull ? b : b.union(contentBounds.insetBy(dx: -6 * unitsPerMM, dy: -6 * unitsPerMM))
        }
        return b
    }
    func paperColor(_ page: Page, printing: Bool = false) -> NSColor {
        if printing { return .white }
        let imported = page.id.hasPrefix("page-notein-") || page.id.hasPrefix("page-onenote-")
        let raw = page.json["appearance"]["pageColor"].string
        if !imported, !raw.isEmpty {
            let color = NSColor(hex: raw)
            if !color.neutralDark && !color.neutralLight { return color }
        }
        return NSColor(hex: page.dark ? "#1B1B1B" : "#FAFAFA")
    }
    func inkColor(_ item: JSON, page: Page, printing: Bool) -> NSColor {
        let explicit = item["foregroundHex"].string.isEmpty ? item["colorHex"].string : item["foregroundHex"].string
        let c = !explicit.isEmpty ? NSColor(hex: explicit) : item["color"].number() == 1 ? NSColor(hex: "#0078D4") : item["color"].number() == 2 ? NSColor(hex: "#C42B1C") : NSColor(hex: page.dark ? "#F4F4F4" : "#151515")
        let imported = page.id.hasPrefix("page-notein-") || page.id.hasPrefix("page-onenote-")
        if printing && c.neutralLight { return .black }
        if imported && page.paper != "PDF" {
            if page.dark && c.neutralDark { return NSColor(hex: "#F4F4F4") }
            if !page.dark && c.neutralLight { return NSColor(hex: "#151515") }
        }
        return c
    }
    func opacity(_ item: JSON) -> Double {
        if item["vectorPath"].string.isEmpty, item["brushProfile"].string != "shape-v1", item["kind"].number() == 0 { return 1 }
        return min(1, max(0, item["opacityPercent"].number(100) / 100))
    }
    func drawPaper(_ page: Page, in c: CGContext, visible: CGRect, scale: Double = 1, printing: Bool = false, hidePattern: Bool = false) {
        let gap = catalog["a4PageGap"].number(32), h = page.height, w = page.width
        let legacy = page.id.hasPrefix("page-notein-") || page.id.hasPrefix("page-onenote-")
        let continuous = legacy && page.paper != "PDF"
        let first = page.paper == "A4" && !continuous ? max(0, Int(visible.minY / (h + gap))) : 0
        let last = page.paper == "A4" && !continuous ? min(page.sheetCount - 1, max(0, Int(visible.maxY / (h + gap)))) : 0
        guard first <= last else { return }
        for index in first...last {
            let r = continuous ? paperBounds(page) : CGRect(x: 0, y: Double(index) * (h + gap), width: w, height: h)
            let clip = visible.intersection(r); guard !clip.isNull else { continue }
            c.saveGState(); c.setFillColor(paperColor(page, printing: printing).cgColor); c.fill(r); c.clip(to: r)
            let raw = page.view["backgroundFormat"].string.replacingOccurrences(of: "RULED_", with: "RULE_")
            if !hidePattern, let format = catalog["formats"].array.first(where: { $0["id"].string == raw }), format["category"].string != "BLANK" {
                let step = format["spacing"].number(32), category = format["category"].string
                let color = NSColor(hex: catalog["dark"]["line"].string)
                c.setStrokeColor(color.cgColor); c.setFillColor(color.cgColor)
                c.setLineWidth(catalog["lineWidth"].number(0.8) / (printing ? 1 : max(0.01, scale)))
                let x0 = floor(clip.minX / step) * step, y0 = r.minY + floor((clip.minY - r.minY) / step) * step
                if category == "DOTS" {
                    let radius = catalog["dotRadius"].number(1)
                    for y in stride(from: y0, through: clip.maxY, by: step) { for x in stride(from: x0, through: clip.maxX, by: step) { c.fillEllipse(in: CGRect(x: x - radius, y: y - radius, width: 2 * radius, height: 2 * radius)) } }
                } else {
                    for y in stride(from: y0, through: clip.maxY, by: step) { c.move(to: CGPoint(x: clip.minX, y: y)); c.addLine(to: CGPoint(x: clip.maxX, y: y)) }
                    if category == "GRID" { for x in stride(from: x0, through: clip.maxX, by: step) { c.move(to: CGPoint(x: x, y: clip.minY)); c.addLine(to: CGPoint(x: x, y: clip.maxY)) } }
                    if format["divider"].bool() { c.move(to: CGPoint(x: w / 2, y: r.minY)); c.addLine(to: CGPoint(x: w / 2, y: r.maxY)) }
                    c.strokePath()
                }
            }
            c.restoreGState()
        }
        if page.view["pageTitleVisible"].bool(true) {
            (page.title as NSString).draw(in: CGRect(x: 24, y: 20, width: w - 48, height: 38), withAttributes: [.font: NSFont.systemFont(ofSize: 23, weight: .semibold), .foregroundColor: printing ? NSColor.black : page.dark ? NSColor.white : NSColor.black])
        }
    }
    func draw(_ d: PageDocument, page: Page, in c: CGContext, visible: CGRect, printing: Bool = false) {
        for (category, item, bounds) in scene where bounds.intersects(visible) {
            c.saveGState()
            if category == "strokes", let path = paths[item["id"].string] {
                c.setFillColor(inkColor(item, page: page, printing: printing).cgColor); c.setAlpha(opacity(item)); c.addPath(path); c.fillPath(using: InkPath.fillRule(item))
            } else if category == "textItems" {
                let paragraph = NSMutableParagraphStyle(); paragraph.lineSpacing = max(0, item["lineSpacing"].number(1) - 1) * item["fontSize"].number(16)
                paragraph.alignment = item["alignment"].string == "Center" ? .center : item["alignment"].string == "Right" ? .right : .left
                var font = NSFont.systemFont(ofSize: item["fontSize"].number(16), weight: item["bold"].bool() ? .bold : .regular)
                if item["italic"].bool() { font = NSFontManager.shared.convert(font, toHaveTrait: .italicFontMask) }
                var attributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: inkColor(item, page: page, printing: printing), .paragraphStyle: paragraph]
                if item["underline"].bool() { attributes[.underlineStyle] = NSUnderlineStyle.single.rawValue }
                if item["strikethrough"].bool() { attributes[.strikethroughStyle] = NSUnderlineStyle.single.rawValue }
                (item["text"].string as NSString).draw(in: bounds, withAttributes: attributes)
            } else if category == "tags" {
                ((item["kind"].number() == 1 ? "?" : "!") as NSString).draw(in: bounds, withAttributes: [.font: NSFont.systemFont(ofSize: bounds.height, weight: .bold), .foregroundColor: NSColor.systemRed])
            } else { drawObject(item, document: d, page: page, in: c, printing: printing) }
            c.restoreGState()
        }
    }
    private func drawObject(_ item: JSON, document d: PageDocument, page: Page, in c: CGContext, printing: Bool) {
        let r = Geometry.bounds(item, category: "objects"), kind = Int(item["kind"].number(2))
        if [2, 3, 5].contains(kind) {
            c.translateBy(x: r.midX, y: r.midY); c.rotate(by: item["rotationQuarterTurns"].number() * .pi / 2)
            c.scaleBy(x: item["flipHorizontal"].bool() ? -1 : 1, y: item["flipVertical"].bool() ? -1 : 1)
            let frame = CGRect(x: -r.width / 2, y: -r.height / 2, width: r.width, height: r.height)
            if let url = try? store.assetURL(item["sourceAssetName"].string, document: d), item["sourcePageNumber"].number() >= 1 {
                let pdf = pdfs[url.path] ?? CGPDFDocument(url as CFURL)
                if let pdf { pdfs[url.path] = pdf }
                if let source = pdf?.page(at: Int(item["sourcePageNumber"].number())) {
                    let media = source.getBoxRect(.mediaBox)
                    c.translateBy(x: frame.minX, y: frame.maxY); c.scaleBy(x: frame.width / media.width, y: -frame.height / media.height)
                    c.translateBy(x: -media.minX, y: -media.minY); c.drawPDFPage(source); return
                }
            }
            if let url = try? store.assetURL(item["assetName"].string, document: d) {
                let key = url.path
                let image = images[key] ?? NSImage(contentsOf: url)
                if let image { if images.count >= 32 { images.removeAll() }; images[key] = image; image.draw(in: frame, from: .zero, operation: .sourceOver, fraction: 1, respectFlipped: true, hints: nil); return }
            }
            c.setFillColor(NSColor.darkGray.cgColor); c.fill(frame)
        } else if kind == 0 {
            let rows = min(200, max(1, Int(item["rows"].number(3)))), cols = min(50, max(1, Int(item["columns"].number(3))))
            c.setStrokeColor(NSColor.gray.cgColor); c.setLineWidth(0.6)
            for row in 0..<rows { for col in 0..<cols {
                let cell = CGRect(x: r.minX + Double(col) * r.width / Double(cols), y: r.minY + Double(row) * r.height / Double(rows), width: r.width / Double(cols), height: r.height / Double(rows))
                c.stroke(cell); let i = row * cols + col, cells = item["cells"].array
                if i < cells.count { (cells[i].string as NSString).draw(in: cell.insetBy(dx: 4, dy: 3), withAttributes: [.font: NSFont.systemFont(ofSize: 14), .foregroundColor: printing ? NSColor.black : page.dark ? NSColor.white : NSColor.black]) }
            } }
        } else {
            c.setFillColor(NSColor(hex: page.dark && !printing ? "#343039" : "#EEEAF5").cgColor); c.fill(r)
            (item["displayName"].string as NSString).draw(in: r.insetBy(dx: 12, dy: 12), withAttributes: [.font: NSFont.systemFont(ofSize: 16), .foregroundColor: printing ? NSColor.black : page.dark ? NSColor.white : NSColor.black])
        }
    }
    func hit(_ point: CGPoint, radius: Double) -> (String, JSON)? {
        for (category, item, bounds) in scene.reversed() where !item["isPageBackground"].bool() && bounds.insetBy(dx: -radius, dy: -radius).contains(point) {
            if category == "strokes", let path = paths[item["id"].string] {
                if path.contains(point, using: InkPath.fillRule(item)) || Geometry.strokeTouches(item, point: point, radius: radius) { return (category, item) }
            } else { return (category, item) }
        }; return nil
    }
    func bounds(for ids: Set<String>) -> CGRect { scene.filter { ids.contains($0.1["id"].string) }.reduce(CGRect.null) { $0.union($1.2) } }
    func stroke(at point: CGPoint, radius: Double) -> Set<String> {
        Set(scene.compactMap { category, item, bounds in
            guard category == "strokes", bounds.insetBy(dx: -radius, dy: -radius).contains(point) else { return nil }
            if let path = paths[item["id"].string], path.contains(point, using: InkPath.fillRule(item)) { return item["id"].string }
            return Geometry.strokeTouches(item, point: point, radius: radius) ? item["id"].string : nil
        })
    }
}