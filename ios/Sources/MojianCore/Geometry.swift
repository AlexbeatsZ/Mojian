import Foundation

public struct Viewport: Equatable, Sendable {
    public static let presets: [Double] = [0.5,0.75,1,1.25,1.5,2,4,8,16,32,64]
    public var scale: Double = 1, x: Double = 0, y: Double = 0
    public init(scale: Double = 1, x: Double = 0, y: Double = 0) { self.scale = min(64, max(0.5, scale)); self.x = x; self.y = y }
    public func document(_ point: CGPoint) -> CGPoint { CGPoint(x: (point.x-x)/scale, y: (point.y-y)/scale) }
    public func screen(_ point: CGPoint) -> CGPoint { CGPoint(x: point.x*scale+x, y: point.y*scale+y) }
    public mutating func zoom(_ requested: Double, anchor: CGPoint) {
        let d = document(anchor); scale = min(64, max(0.5, requested)); x = anchor.x-d.x*scale; y = anchor.y-d.y*scale
    }
}
public enum ShapeKind: String, CaseIterable, Codable, Sendable {
    case line, arrow, circle, ellipse, rectangle, triangle, diamond
    public var title: String {
        switch self { case .line: return "直线"; case .arrow: return "箭头"; case .circle: return "圆"
        case .ellipse: return "椭圆"; case .rectangle: return "矩形"; case .triangle: return "三角形"; case .diamond: return "菱形" }
    }
    public func points(from a: CGPoint, to b: CGPoint) -> [InkSample] {
        let r = CGRect(x: min(a.x,b.x), y: min(a.y,b.y), width: abs(b.x-a.x), height: abs(b.y-a.y))
        var p: [CGPoint]
        switch self {
        case .line: p = [a,b]
        case .arrow:
            let angle = atan2(b.y-a.y,b.x-a.x), l = min(24, hypot(b.x-a.x,b.y-a.y)*0.25)
            p = [a,b,CGPoint(x:b.x-l*cos(angle-0.5),y:b.y-l*sin(angle-0.5)),b,CGPoint(x:b.x-l*cos(angle+0.5),y:b.y-l*sin(angle+0.5))]
        case .circle, .ellipse:
            let rx = self == .circle ? min(r.width,r.height)/2 : r.width/2, ry = self == .circle ? rx : r.height/2
            p = (0...96).map { let t=Double($0)*2*Double.pi/96; return CGPoint(x:r.midX+rx*cos(t),y:r.midY+ry*sin(t)) }
        case .rectangle: p = [CGPoint(x:r.minX,y:r.minY),CGPoint(x:r.maxX,y:r.minY),CGPoint(x:r.maxX,y:r.maxY),CGPoint(x:r.minX,y:r.maxY),CGPoint(x:r.minX,y:r.minY)]
        case .triangle: p = [CGPoint(x:r.midX,y:r.minY),CGPoint(x:r.maxX,y:r.maxY),CGPoint(x:r.minX,y:r.maxY),CGPoint(x:r.midX,y:r.minY)]
        case .diamond: p = [CGPoint(x:r.midX,y:r.minY),CGPoint(x:r.maxX,y:r.midY),CGPoint(x:r.midX,y:r.maxY),CGPoint(x:r.minX,y:r.midY),CGPoint(x:r.midX,y:r.minY)]
        }
        return p.enumerated().map { InkSample(x:$0.element.x,y:$0.element.y,elapsed:Double($0.offset)*8) }
    }
}
public enum Geometry {
    public static func bounds(_ item: JSON, category: String) -> CGRect {
        if category == "strokes" {
            if item["vectorBounds"].array.count == 4 {
                let b = item["vectorBounds"].array.map { $0.number() }, m = item["vectorTransform"].array.map { $0.number() }
                let r = CGRect(x:b[0],y:b[1],width:max(0.01,b[2]-b[0]),height:max(0.01,b[3]-b[1]))
                if m.count == 6 { return rect(points: [CGPoint(x:r.minX,y:r.minY),CGPoint(x:r.maxX,y:r.minY),CGPoint(x:r.maxX,y:r.maxY),CGPoint(x:r.minX,y:r.maxY)].map { CGPoint(x:$0.x*m[0]+$0.y*m[2]+m[4],y:$0.x*m[1]+$0.y*m[3]+m[5]) }) }
                return r
            }
            return rect(points: item["points"].array.map { InkSample($0).point }).insetBy(dx:-max(0.35,item["widthMm"].number(0.4)*unitsPerMM/2),dy:-max(0.35,item["widthMm"].number(0.4)*unitsPerMM/2))
        }
        if category == "tags" { return CGRect(x:item["documentX"].number(),y:item["documentY"].number(),width:24*item["scale"].number(1),height:24*item["scale"].number(1)) }
        return CGRect(x:item["x"].number(),y:item["y"].number(),width:max(0.01,item["width"].number(240)),height:max(0.01,item["height"].number(60)))
    }
    public static func rect(points: [CGPoint]) -> CGRect {
        guard let first = points.first else { return .null }
        var minX=first.x,minY=first.y,maxX=first.x,maxY=first.y
        for p in points { minX=min(minX,p.x); minY=min(minY,p.y); maxX=max(maxX,p.x); maxY=max(maxY,p.y) }
        return CGRect(x:minX,y:minY,width:max(0.01,maxX-minX),height:max(0.01,maxY-minY))
    }
    public static func contains(_ point: CGPoint, polygon: [CGPoint]) -> Bool {
        guard polygon.count >= 3 else { return false }
        var inside=false, j=polygon.count-1
        for i in polygon.indices {
            let a=polygon[i], b=polygon[j]
            if distance(point, segment:a, end:b) < 0.001 { return true }
            if (a.y>point.y) != (b.y>point.y), point.x < (b.x-a.x)*(point.y-a.y)/(b.y-a.y)+a.x { inside.toggle() }
            j=i
        }; return inside
    }
    public static func selected(in document: PageDocument, polygon: [CGPoint]) -> Set<String> {
        var ids=Set<String>()
        for key in ["strokes","objects","textItems","tags"] {
            for item in document.json[key].array where !item["isPageBackground"].bool() {
                let b=bounds(item,category:key)
                let samples = key == "strokes" && item["vectorPath"].string.isEmpty ? item["points"].array.map { InkSample($0).point } :
                    [CGPoint(x:b.minX,y:b.minY),CGPoint(x:b.maxX,y:b.minY),CGPoint(x:b.maxX,y:b.maxY),CGPoint(x:b.minX,y:b.maxY)]
                if !samples.isEmpty && samples.allSatisfy({ contains($0,polygon:polygon) }) { ids.insert(item["id"].string) }
            }
        }; return ids
    }
    public static func distance(_ p: CGPoint, segment a: CGPoint, end b: CGPoint) -> Double {
        let dx=b.x-a.x,dy=b.y-a.y,l=dx*dx+dy*dy
        let t=l>0 ? min(1,max(0,((p.x-a.x)*dx+(p.y-a.y)*dy)/l)) : 0
        return hypot(p.x-a.x-t*dx,p.y-a.y-t*dy)
    }
    public static func strokeTouches(_ stroke: JSON, point: CGPoint, radius: Double) -> Bool {
        let b=bounds(stroke,category:"strokes").insetBy(dx:-radius,dy:-radius)
        guard b.contains(point) else { return false }
        let p=stroke["points"].array.map { InkSample($0).point }
        if p.count == 1 { return hypot(p[0].x-point.x,p[0].y-point.y)<=radius+stroke["widthMm"].number(0.4)*unitsPerMM/2 }
        return zip(p,p.dropFirst()).contains { distance(point,segment:$0.0,end:$0.1)<=radius+stroke["widthMm"].number(0.4)*unitsPerMM/2 }
    }
    public static func handles(_ b: CGRect) -> [CGPoint] {
        [CGPoint(x:b.minX,y:b.minY),CGPoint(x:b.midX,y:b.minY),CGPoint(x:b.maxX,y:b.minY),CGPoint(x:b.maxX,y:b.midY),CGPoint(x:b.maxX,y:b.maxY),CGPoint(x:b.midX,y:b.maxY),CGPoint(x:b.minX,y:b.maxY),CGPoint(x:b.minX,y:b.midY)]
    }
    public static func handle(at p: CGPoint, bounds b: CGRect, radius: Double) -> Int? {
        if b.insetBy(dx:min(radius,b.width/4),dy:min(radius,b.height/4)).contains(p) { return nil }
        return handles(b).enumerated().filter { hypot($0.element.x-p.x,$0.element.y-p.y)<=radius }.min { hypot($0.element.x-p.x,$0.element.y-p.y)<hypot($1.element.x-p.x,$1.element.y-p.y) }?.offset
    }
    public static func resize(_ b: CGRect, handle: Int, delta: CGPoint, minimum: Double) -> CGRect {
        let points=handles(b), moving=points[handle], anchor=points[(handle+4)%8]
        if handle%2 == 0 {
            let dx=moving.x-anchor.x,dy=moving.y-anchor.y
            let ratio=max(max(minimum/max(0.001,b.width),minimum/max(0.001,b.height)),1+(delta.x*dx+delta.y*dy)/max(0.001,dx*dx+dy*dy))
            return rect(points:[anchor,CGPoint(x:anchor.x+dx*ratio,y:anchor.y+dy*ratio)])
        }
        var r=b
        if handle == 1 { r.origin.y=min(b.maxY-minimum,b.minY+delta.y); r.size.height=b.maxY-r.minY }
        if handle == 3 { r.size.width=max(minimum,b.width+delta.x) }
        if handle == 5 { r.size.height=max(minimum,b.height+delta.y) }
        if handle == 7 { r.origin.x=min(b.maxX-minimum,b.minX+delta.x); r.size.width=b.maxX-r.minX }
        return r
    }
    public static func segmentWidth(_ stroke: JSON, points: [InkSample], index: Int, completed: Bool) -> Double {
        guard !points.isEmpty else { return 0.35 }
        let a=points[max(0,index-1)],b=points[min(index,points.count-1)]
        let progress=Double(index)/Double(max(1,points.count-1))
        let taper=completed && points.count>=4 ? 0.24+0.76*min(1,max(0,min(progress/0.12,(1-progress)/0.16))) : 1
        let curve=pow(min(1,max(0.03,a.pressure)),2/max(0.2,stroke["pressurePercent"].number(200)/100))
        let speed=hypot(b.x-a.x,b.y-a.y)/max(1,b.elapsed-a.elapsed)
        let flow=1/(1+speed*max(0,stroke["inkFlowPercent"].number(100)/100)*0.14)
        return max(0.35,stroke["widthMm"].number(0.4)*unitsPerMM*(0.38+curve*0.78)*(0.72+flow*0.28)*taper)
    }
}

public struct PressureEraser: Sendable {
    private var previous: InkSample?, origin: InkSample?, accumulated=0.0
    public private(set) var locked=false, active=false
    public init() {}
    public mutating func update(_ sample: InkSample, settings: Settings, eligible: Bool) -> Bool {
        if let p=previous { accumulated += hypot(sample.x-p.x,sample.y-p.y)/unitsPerMM }; previous=sample
        if active { return true }
        if accumulated>settings.travelMM { locked=true; origin=nil }
        guard eligible, settings.pressureErase, !locked, sample.pressure>=settings.pressureThreshold else { origin=nil; return false }
        if let o=origin, hypot(sample.x-o.x,sample.y-o.y)<=2/unitsPerMM {
            if sample.elapsed-o.elapsed>=settings.dwellMS { active=true; return true }
        } else { origin=sample }
        return false
    }
}
