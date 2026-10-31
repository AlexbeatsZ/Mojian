import AppKit
import MojianCore

@MainActor final class NoteCanvas: NSView {
    let session: DesktopSession
    let renderer: MacRenderer
    var viewport: Viewport
    var selected = Set<String>()
    var mouseWriting = false
    var viewportChanged: (() -> Void)?
    var editText: ((JSON?, CGPoint) -> Void)?
    private var points = [InkSample]()
    private var before: PageDocument?
    private var start = CGPoint.zero
    private var previous = CGPoint.zero
    private var gesture = ""
    private var resizeHandle: Int?
    private var originalBounds = CGRect.null
    private var startTime = 0.0
    private var tracking: NSTrackingArea?
    override var isFlipped: Bool { true }
    override var acceptsFirstResponder: Bool { true }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
    init(session: DesktopSession) {
        self.session = session; renderer = MacRenderer(store: session.store); viewport = session.viewport()
        super.init(frame: .zero)
        wantsLayer = true; layer?.backgroundColor = NSColor(hex: "#0A0A0A").cgColor
        setAccessibilityLabel("笔记画布"); setAccessibilityRole(.group)
        if let d = session.document { renderer.bind(d) }
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }
    func rebind(resetViewport: Bool = false) {
        if resetViewport { viewport = session.viewport(); selected.removeAll() }
        if let d = session.document { renderer.bind(d) }
        needsDisplay = true
    }
    var visibleDocument: CGRect {
        let a = viewport.document(.zero), b = viewport.document(CGPoint(x: bounds.width, y: bounds.height))
        return CGRect(x: a.x, y: a.y, width: b.x - a.x, height: b.y - a.y)
    }
    override func draw(_ dirtyRect: NSRect) {
        NSColor(hex: "#0A0A0A").setFill(); bounds.fill()
        guard let d = session.document, let page = session.displayPage, let c = NSGraphicsContext.current?.cgContext else {
            let text = session.store.readOnly ? "选择一个页面开始阅读" : "创建或选择一个页面"
            (text as NSString).draw(at: CGPoint(x: 40, y: 80), withAttributes: [.font: NSFont.systemFont(ofSize: 20), .foregroundColor: NSColor.secondaryLabelColor]); return
        }
        c.saveGState(); c.translateBy(x: viewport.x, y: viewport.y); c.scaleBy(x: viewport.scale, y: viewport.scale)
        renderer.drawPaper(page, in: c, visible: visibleDocument, scale: viewport.scale)
        renderer.draw(d, page: page, in: c, visible: visibleDocument)
        if gesture == "ink" || gesture == "shape", !points.isEmpty {
            let samples = gesture == "shape" ? session.settings.shape.points(from: points[0].point, to: points.last!.point) : points
            let stroke = session.settings.stroke(samples, shape: gesture == "shape")
            c.setFillColor(renderer.inkColor(stroke, page: page, printing: false).cgColor)
            c.setAlpha(renderer.opacity(stroke)); c.addPath(InkPath.make(stroke, completed: false)); c.fillPath()
        }
        c.setAlpha(1)
        if gesture == "lasso" || gesture == "marquee", points.count > 1 {
            c.setStrokeColor(NSColor(hex: "#DAB2E9").cgColor); c.setLineWidth(1 / viewport.scale); c.setLineDash(phase: 0, lengths: [4 / viewport.scale, 3 / viewport.scale])
            if gesture == "marquee" { c.stroke(Geometry.rect(points: [points[0].point, points.last!.point])) }
            else { c.beginPath(); c.move(to: points[0].point); for p in points.dropFirst() { c.addLine(to: p.point) }; c.strokePath() }
        }
        let selection = renderer.bounds(for: selected)
        if !selection.isNull {
            c.setStrokeColor(NSColor(hex: "#DAB2E9").cgColor); c.setFillColor(NSColor(hex: "#1B1B1B").cgColor)
            c.setLineWidth(1 / viewport.scale); c.setLineDash(phase: 0, lengths: [3 / viewport.scale, 3 / viewport.scale]); c.stroke(selection)
            c.setLineDash(phase: 0, lengths: [])
            for handle in Geometry.handles(selection) {
                let r = CGRect(x: handle.x - 3 / viewport.scale, y: handle.y - 3 / viewport.scale, width: 6 / viewport.scale, height: 6 / viewport.scale)
                c.fill(r); c.stroke(r)
            }
        }
        c.restoreGState()
    }
    private func location(_ event: NSEvent) -> CGPoint { convert(event.locationInWindow, from: nil) }
    private func sample(_ event: NSEvent) -> InkSample {
        let p = viewport.document(location(event)), tablet = event.subtype == .tabletPoint
        return InkSample(x: p.x, y: p.y, pressure: tablet ? Double(event.pressure) : 0.5,
                         elapsed: max(0, (event.timestamp - startTime) * 1000), tiltX: tablet ? event.tilt.x : 0, tiltY: tablet ? event.tilt.y : 0)
    }
    override func mouseDown(with event: NSEvent) { begin(event, forced: nil) }
    override func rightMouseDown(with event: NSEvent) { begin(event, forced: "lasso") }
    override func otherMouseDown(with event: NSEvent) { begin(event, forced: "pan") }
    private func begin(_ event: NSEvent, forced: String?) {
        window?.makeFirstResponder(self)
        guard session.document != nil else { return }
        startTime = event.timestamp; start = viewport.document(location(event)); previous = location(event)
        points = [sample(event)]; before = session.document
        if forced == "pan" || event.modifierFlags.contains(.option) { gesture = "pan"; return }
        guard session.canEdit else { gesture = "pan"; return }
        if let forced { gesture = forced; needsDisplay = true; return }
        let tablet = event.subtype == .tabletPoint
        if tablet || mouseWriting {
            switch session.settings.tool {
            case .pen, .highlighter: gesture = "ink"
            case .shape: gesture = "shape"
            case .eraser: gesture = "erase"; erase(at: start)
            case .lasso: gesture = "lasso"
            case .hand: gesture = "pan"
            }
            needsDisplay = true; return
        }
        originalBounds = renderer.bounds(for: selected)
        resizeHandle = originalBounds.isNull ? nil : Geometry.handle(at: start, bounds: originalBounds, radius: 8 / viewport.scale)
        if resizeHandle != nil || (!originalBounds.isNull && originalBounds.contains(start)) { gesture = "transform"; return }
        if let (category, item) = renderer.hit(start, radius: 3 / viewport.scale) {
            selected = [item["id"].string]
            if event.clickCount >= 2, category == "textItems" { editText?(item, start); gesture = "" }
            else { originalBounds = renderer.bounds(for: selected); resizeHandle = nil; gesture = "transform" }
        } else { selected.removeAll(); gesture = "marquee" }
        needsDisplay = true
    }
    override func mouseDragged(with event: NSEvent) { move(event) }
    override func rightMouseDragged(with event: NSEvent) { move(event) }
    override func otherMouseDragged(with event: NSEvent) { move(event) }
    private func move(_ event: NSEvent) {
        let s = sample(event), screen = location(event)
        switch gesture {
        case "pan":
            viewport.x += screen.x - previous.x; viewport.y += screen.y - previous.y
            session.remember(viewport); viewportChanged?()
        case "ink", "shape", "marquee", "lasso": points.append(s)
        case "erase": erase(at: s.point)
        case "transform":
            guard var d = before, !originalBounds.isNull else { break }
            let delta = CGPoint(x: s.x - start.x, y: s.y - start.y)
            let target = resizeHandle.map { Geometry.resize(originalBounds, handle: $0, delta: delta, minimum: 2 / viewport.scale) } ?? originalBounds.offsetBy(dx: delta.x, dy: delta.y)
            d.transform(ids: selected, from: originalBounds, to: target); session.document = d; renderer.bind(d)
        default: break
        }
        previous = screen; needsDisplay = true
    }
    override func mouseUp(with event: NSEvent) { end(event) }
    override func rightMouseUp(with event: NSEvent) { end(event) }
    override func otherMouseUp(with event: NSEvent) { end(event) }
    private func end(_ event: NSEvent) {
        if gesture == "ink" || gesture == "shape" {
            points.append(sample(event))
            let shape = gesture == "shape", samples = shape ? session.settings.shape.points(from: points[0].point, to: points.last!.point) : points
            let stroke = session.settings.stroke(samples, shape: shape)
            session.mutate { $0.strokes.append(stroke) }
        } else if gesture == "transform" || gesture == "erase", let before { session.commitPreview(before) }
        else if gesture == "marquee" || gesture == "lasso", let document = session.document {
            let polygon: [CGPoint]
            if gesture == "marquee", let first = points.first, let last = points.last {
                let b = Geometry.rect(points: [first.point, last.point])
                polygon = [b.origin, CGPoint(x: b.maxX, y: b.minY), CGPoint(x: b.maxX, y: b.maxY), CGPoint(x: b.minX, y: b.maxY)]
            } else { polygon = points.map(\.point) }
            selected = Geometry.selected(in: document, polygon: polygon)
        }
        gesture = ""; points.removeAll(); before = nil
        if let d = session.document { renderer.bind(d) }; needsDisplay = true
    }
    private func erase(at point: CGPoint) {
        guard var d = session.document else { return }
        let ids = renderer.stroke(at: point, radius: session.settings.eraserRadiusMM * unitsPerMM)
        guard !ids.isEmpty else { return }
        d.strokes.removeAll { ids.contains($0["id"].string) }; session.document = d; renderer.bind(d)
    }
    override func scrollWheel(with event: NSEvent) {
        if event.modifierFlags.contains(.command) {
            viewport.zoom(viewport.scale * exp(-Double(event.scrollingDeltaY) * 0.01), anchor: location(event))
        } else { viewport.x -= event.scrollingDeltaX; viewport.y -= event.scrollingDeltaY }
        session.remember(viewport); viewportChanged?(); needsDisplay = true
    }
    override func magnify(with event: NSEvent) {
        viewport.zoom(viewport.scale * (1 + event.magnification), anchor: location(event))
        session.remember(viewport); viewportChanged?(); needsDisplay = true
    }
    func zoom(_ value: Double) {
        viewport.zoom(value, anchor: CGPoint(x: bounds.midX, y: bounds.midY))
        session.remember(viewport); viewportChanged?(); needsDisplay = true
    }
    func fitPage() {
        guard let page = session.displayPage else { return }
        let b = renderer.paperBounds(page), scale = min(64, max(0.5, (bounds.width - 64) / max(1, b.width)))
        viewport = Viewport(scale: scale, x: (bounds.width - b.width * scale) / 2 - b.minX * scale, y: 24 - b.minY * scale)
        session.remember(viewport); viewportChanged?(); needsDisplay = true
    }
    func deleteSelection() {
        guard !selected.isEmpty else { return }
        let ids = selected; session.mutate { $0.delete(ids: ids) }; selected.removeAll(); rebind()
    }
    override func keyDown(with event: NSEvent) {
        if event.keyCode == 51 || event.keyCode == 117 { deleteSelection() }
        else if event.keyCode == 53 { selected.removeAll(); needsDisplay = true }
        else { super.keyDown(with: event) }
    }
    override func cancelOperation(_ sender: Any?) {
        if !gesture.isEmpty, let before { session.document = before; rebind() }
        gesture = ""; points.removeAll(); before = nil; selected.removeAll(); needsDisplay = true
    }
}