import AppKit
import MojianCore

/// Dark workspace palette shared with Android/Windows (`docs/design/windows-desktop.md`, visual system).
enum Palette {
    static let chrome = NSColor(hex: "#111111")
    static let toolbar = NSColor(hex: "#232323")
    static let rail = NSColor(hex: "#141414")
    static let sectionPane = NSColor(hex: "#0F0F0F")
    static let pagePane = NSColor(hex: "#2A2A2A")
    static let workspace = NSColor(hex: "#0A0A0A")
    static let border = NSColor(hex: "#3D3D3D")
    static let selected = NSColor(hex: "#374048")
    static let selectedBorder = NSColor(hex: "#56616C")
    static let hover = NSColor(hex: "#2D2D2D")
    static let pressed = NSColor(hex: "#3A3A3A")
    static let canvasAction = NSColor(hex: "#1B1B1B")
    static let accent = NSColor(hex: "#DAB2E9")
    static let text = NSColor(hex: "#F4F4F4")
    static let secondary = NSColor(hex: "#B9B9B9")
    static let muted = NSColor(hex: "#828282")
    static let sections = ["#7719AA", "#185ABD", "#C239B3", "#CA5010"].map { NSColor(hex: $0) }
    /// Ribbon/preset display color: logical black is shown light on the dark chrome, as on Windows.
    static func ink(_ color: Int) -> NSColor { NSColor(hex: color == 1 ? "#185ABD" : color == 2 ? "#C42B1C" : "#EFEFEF") }
    static func swatch(_ color: Int) -> NSColor { NSColor(hex: color == 1 ? "#185ABD" : color == 2 ? "#C42B1C" : "#1B1B1F") }
}

/// Fluent UI System Icons generated from the Android drawables by `macos/tools/generate-icons.py`.
@MainActor enum FluentIcon {
    static let catalog: JSON = {
        guard let url = Bundle.module.url(forResource: "fluent-icons", withExtension: "json"), let data = try? Data(contentsOf: url) else { return .object([:]) }
        return (try? JSON.read(data)) ?? .object([:])
    }()
    private static var cache = [String: [(CGPath, NSColor?, Bool)]]()
    private static func layers(_ name: String) -> [(CGPath, NSColor?, Bool)] {
        if let hit = cache[name] { return hit }
        let value = catalog[name]["paths"].array.map { p in
            (path(p["d"].string), p["color"].string.isEmpty ? nil : NSColor(hex: p["color"].string), p["evenOdd"].bool())
        }
        cache[name] = value; return value
    }
    static func path(_ d: String) -> CGPath {
        let path = CGMutablePath()
        var tokens = d.split(separator: " ").makeIterator()
        func number() -> CGFloat { CGFloat(Double(tokens.next() ?? "0") ?? 0) }
        func point() -> CGPoint { let x = number(); return CGPoint(x: x, y: number()) }
        while let command = tokens.next() {
            switch command {
            case "M": path.move(to: point())
            case "L": path.addLine(to: point())
            case "C": let a = point(), b = point(); path.addCurve(to: point(), control1: a, control2: b)
            case "Z": path.closeSubpath()
            default: break
            }
        }
        return path
    }
    /// `fit` scales the drawn geometry to the image box like WPF `Stretch=Uniform` (used for carets).
    static func image(_ name: String, size: CGFloat = 20, tint: NSColor = Palette.text, fit: Bool = false) -> NSImage {
        let parts = layers(name), icon = catalog[name]
        let source = fit ? parts.reduce(CGRect.null) { $0.union($1.0.boundingBoxOfPath) } : CGRect(x: 0, y: 0, width: icon["width"].number(24), height: icon["height"].number(24))
        let image = NSImage(size: NSSize(width: size, height: size), flipped: true) { _ in
            guard let c = NSGraphicsContext.current?.cgContext, !source.isNull, source.width > 0, source.height > 0 else { return false }
            let scale = min(size / source.width, size / source.height)
            c.translateBy(x: (size - source.width * scale) / 2, y: (size - source.height * scale) / 2)
            c.scaleBy(x: scale, y: scale); c.translateBy(x: -source.minX, y: -source.minY)
            for (path, color, evenOdd) in parts { c.addPath(path); c.setFillColor((color ?? tint).cgColor); c.fillPath(using: evenOdd ? .evenOdd : .winding) }
            return true
        }
        image.accessibilityDescription = name
        return image
    }
    static func swatch(_ color: NSColor, size: CGFloat = 18, ring: Bool = false) -> NSImage {
        NSImage(size: NSSize(width: size, height: size), flipped: true) { rect in
            let oval = NSBezierPath(ovalIn: rect.insetBy(dx: 1, dy: 1)); color.setFill(); oval.fill()
            if ring { NSColor.white.setStroke(); oval.lineWidth = 1; oval.stroke() }
            return true
        }
    }
}

/// Solid chrome surface with optional one-physical-pixel hairlines.
@MainActor class FillView: NSView {
    var fill: NSColor { didSet { needsDisplay = true } }
    var top = false, bottom = false, left = false, right = false
    init(_ fill: NSColor) { self.fill = fill; super.init(frame: .zero); translatesAutoresizingMaskIntoConstraints = false }
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }
    override var isFlipped: Bool { true }
    override func draw(_ dirtyRect: NSRect) {
        fill.setFill(); dirtyRect.fill()
        let px = 1 / max(1, window?.backingScaleFactor ?? 2)
        Palette.border.setFill()
        if top { CGRect(x: 0, y: 0, width: bounds.width, height: px).fill() }
        if bottom { CGRect(x: 0, y: bounds.height - px, width: bounds.width, height: px).fill() }
        if left { CGRect(x: 0, y: 0, width: px, height: bounds.height).fill() }
        if right { CGRect(x: bounds.width - px, y: 0, width: px, height: bounds.height).fill() }
    }
}

/// Compact icon-only ribbon command with Windows hover/pressed/selected states.
@MainActor final class RibbonButton: NSView {
    var icon: NSImage? { didSet { needsDisplay = true } }
    var text: String? { didSet { needsDisplay = true } }
    var isSelected = false { didSet { if oldValue != isSelected { needsDisplay = true; setAccessibilityValue(isSelected ? "已选中" : nil) } } }
    var isEnabled = true { didSet { if oldValue != isEnabled { needsDisplay = true } } }
    /// Selected presets show the small flyout caret; dropdowns show a caret beside their value.
    var showsCaret = false
    var dropdown = false
    /// Canvas actions keep a visible resting surface over paper.
    var resting: NSColor?
    var onPress: ((RibbonButton) -> Void)?
    private var hovering = false, pressing = false, tracking: NSTrackingArea?
    init(icon: String? = nil, text: String? = nil, tip: String, size: NSSize = NSSize(width: 40, height: 42), iconSize: CGFloat = 21, tint: NSColor = Palette.text, onPress: ((RibbonButton) -> Void)? = nil) {
        self.text = text; self.onPress = onPress
        super.init(frame: CGRect(origin: .zero, size: size))
        if let icon { self.icon = FluentIcon.image(icon, size: iconSize, tint: tint) }
        toolTip = tip; setAccessibilityElement(true); setAccessibilityRole(.button); setAccessibilityLabel(tip)
        translatesAutoresizingMaskIntoConstraints = false
        widthAnchor.constraint(equalToConstant: size.width).isActive = true
        heightAnchor.constraint(equalToConstant: size.height).isActive = true
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }
    override var isFlipped: Bool { true }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
    override func updateTrackingAreas() {
        if let tracking { removeTrackingArea(tracking) }
        let area = NSTrackingArea(rect: .zero, options: [.mouseEnteredAndExited, .activeInKeyWindow, .inVisibleRect], owner: self)
        addTrackingArea(area); tracking = area; super.updateTrackingAreas()
    }
    override func mouseEntered(with event: NSEvent) { hovering = true; needsDisplay = true }
    override func mouseExited(with event: NSEvent) { hovering = false; needsDisplay = true }
    override func mouseDown(with event: NSEvent) { guard isEnabled else { return }; pressing = true; needsDisplay = true }
    override func mouseDragged(with event: NSEvent) {
        let inside = bounds.contains(convert(event.locationInWindow, from: nil))
        if inside != pressing { pressing = inside; needsDisplay = true }
    }
    override func mouseUp(with event: NSEvent) {
        let fire = pressing; pressing = false; needsDisplay = true
        if fire, isEnabled { onPress?(self) }
    }
    override func accessibilityPerformPress() -> Bool { guard isEnabled else { return false }; onPress?(self); return true }
    override func draw(_ dirtyRect: NSRect) {
        let box = resting == nil ? bounds.insetBy(dx: 2, dy: 3) : bounds.insetBy(dx: 0.5, dy: 0.5)
        let shape = NSBezierPath(roundedRect: box, xRadius: 3, yRadius: 3)
        if pressing && isEnabled { Palette.pressed.setFill(); shape.fill() }
        else if isSelected { Palette.selected.setFill(); shape.fill(); Palette.selectedBorder.setStroke(); shape.lineWidth = 1; shape.stroke() }
        else if hovering && isEnabled { Palette.hover.setFill(); shape.fill() }
        else if let resting { resting.setFill(); shape.fill(); Palette.border.setStroke(); shape.lineWidth = 1; shape.stroke() }
        let alpha: CGFloat = isEnabled ? 1 : 0.35, shift: CGFloat = dropdown ? 6 : 0
        if let icon {
            let s = icon.size
            icon.draw(in: CGRect(x: (bounds.width - s.width) / 2 - shift, y: (bounds.height - s.height) / 2, width: s.width, height: s.height), from: .zero, operation: .sourceOver, fraction: alpha, respectFlipped: true, hints: nil)
        }
        if let text {
            let attributes: [NSAttributedString.Key: Any] = [.font: NSFont.monospacedDigitSystemFont(ofSize: 11, weight: .regular), .foregroundColor: Palette.text.withAlphaComponent(alpha)]
            let size = (text as NSString).size(withAttributes: attributes)
            (text as NSString).draw(at: CGPoint(x: (bounds.width - size.width) / 2 - shift, y: (bounds.height - size.height) / 2), withAttributes: attributes)
        }
        if dropdown || (showsCaret && isSelected) {
            let caret = FluentIcon.image("caret_down", size: dropdown ? 9 : 8, tint: dropdown ? Palette.secondary : Palette.text, fit: true)
            let origin = dropdown ? CGPoint(x: bounds.width - 15, y: (bounds.height - 9) / 2) : CGPoint(x: bounds.width - 13, y: bounds.height - 14)
            caret.draw(in: CGRect(origin: origin, size: caret.size), from: .zero, operation: .sourceOver, fraction: alpha, respectFlipped: true, hints: nil)
        }
    }
}

/// Ribbon tab in the unified title bar: lavender text and underline mark the active tab.
@MainActor final class TabButton: NSView {
    let title: String
    var isSelected = false { didSet { needsDisplay = true; setAccessibilityValue(isSelected ? "已选中" : nil) } }
    var onPress: (() -> Void)?
    private var hovering = false, tracking: NSTrackingArea?
    init(title: String, onPress: @escaping () -> Void) {
        self.title = title; self.onPress = onPress
        super.init(frame: CGRect(x: 0, y: 0, width: 52, height: 30))
        setAccessibilityElement(true); setAccessibilityRole(.radioButton); setAccessibilityLabel(title)
        translatesAutoresizingMaskIntoConstraints = false
        widthAnchor.constraint(equalToConstant: 52).isActive = true; heightAnchor.constraint(equalToConstant: 30).isActive = true
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }
    override var isFlipped: Bool { true }
    override var mouseDownCanMoveWindow: Bool { false }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
    override func updateTrackingAreas() {
        if let tracking { removeTrackingArea(tracking) }
        let area = NSTrackingArea(rect: .zero, options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect], owner: self)
        addTrackingArea(area); tracking = area; super.updateTrackingAreas()
    }
    override func mouseEntered(with event: NSEvent) { hovering = true; needsDisplay = true }
    override func mouseExited(with event: NSEvent) { hovering = false; needsDisplay = true }
    override func mouseDown(with event: NSEvent) { onPress?() }
    override func accessibilityPerformPress() -> Bool { onPress?(); return true }
    override func draw(_ dirtyRect: NSRect) {
        if hovering && !isSelected { Palette.hover.setFill(); NSBezierPath(roundedRect: bounds.insetBy(dx: 2, dy: 3), xRadius: 3, yRadius: 3).fill() }
        let attributes: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: 13, weight: isSelected ? .semibold : .regular), .foregroundColor: isSelected ? Palette.accent : Palette.text]
        let size = (title as NSString).size(withAttributes: attributes)
        (title as NSString).draw(at: CGPoint(x: (bounds.width - size.width) / 2, y: (bounds.height - size.height) / 2 - 1), withAttributes: attributes)
        if isSelected { Palette.accent.setFill(); NSBezierPath(roundedRect: CGRect(x: (bounds.width - size.width) / 2 - 4, y: bounds.height - 3, width: size.width + 8, height: 2), xRadius: 1, yRadius: 1).fill() }
    }
}

/// Ribbon group divider: one physical pixel with the shared compact spacing.
@MainActor func ribbonDivider() -> NSView {
    let holder = NSView(), line = FillView(Palette.border)
    holder.translatesAutoresizingMaskIntoConstraints = false; holder.addSubview(line)
    NSLayoutConstraint.activate([
        holder.widthAnchor.constraint(equalToConstant: 11), holder.heightAnchor.constraint(equalToConstant: 42),
        line.widthAnchor.constraint(equalToConstant: 1 / max(1, NSScreen.main?.backingScaleFactor ?? 2)), line.heightAnchor.constraint(equalToConstant: 26),
        line.centerXAnchor.constraint(equalTo: holder.centerXAnchor), line.centerYAnchor.constraint(equalTo: holder.centerYAnchor)
    ])
    return holder
}
