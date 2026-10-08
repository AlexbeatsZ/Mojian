import Foundation
import CoreGraphics

public enum InkPath {
    public static func make(_ stroke: JSON, completed: Bool = true) -> CGPath {
        if !stroke["vectorPath"].string.isEmpty, let source=try? parse(stroke["vectorPath"].string) {
            let m=stroke["vectorTransform"].array.map { $0.number() }
            var t=m.count==6 ? CGAffineTransform(a:m[0],b:m[1],c:m[2],d:m[3],tx:m[4],ty:m[5]) : .identity
            return source.copy(using:&t) ?? source
        }
        let p=stroke["points"].array.map(InkSample.init), path=CGMutablePath()
        guard let first=p.first else { return path }
        let uniform=stroke["brushProfile"].string=="shape-v1" || stroke["kind"].number()==1
        if uniform {
            path.move(to:first.point); for sample in p.dropFirst() { path.addLine(to:sample.point) }
            return path.copy(strokingWithWidth:max(0.35,stroke["widthMm"].number(0.4)*unitsPerMM),lineCap:.round,lineJoin:.round,miterLimit:10)
        }
        if p.count==1 { let r=max(0.35,stroke["widthMm"].number(0.4)*unitsPerMM)/2; path.addEllipse(in:CGRect(x:first.x-r,y:first.y-r,width:r*2,height:r*2)); return path }
        func smooth(_ i:Int) -> CGPoint {
            if i==0 || i==p.count-1 { return p[i].point }
            return CGPoint(x:p[i-1].x*0.2+p[i].x*0.6+p[i+1].x*0.2,y:p[i-1].y*0.2+p[i].y*0.6+p[i+1].y*0.2)
        }
        for i in 1..<p.count {
            let a=smooth(i-1),b=smooth(i),r=Geometry.segmentWidth(stroke,points:p,index:i,completed:completed)/2
            let dx=b.x-a.x,dy=b.y-a.y,l=max(0.00001,hypot(dx,dy)),nx=dy/l*r,ny = -dx/l*r
            path.move(to:CGPoint(x:a.x+nx,y:a.y+ny)); path.addLine(to:CGPoint(x:b.x+nx,y:b.y+ny))
            path.addLine(to:CGPoint(x:b.x-nx,y:b.y-ny)); path.addLine(to:CGPoint(x:a.x-nx,y:a.y-ny)); path.closeSubpath()
            path.addEllipse(in:CGRect(x:a.x-r,y:a.y-r,width:r*2,height:r*2)); path.addEllipse(in:CGRect(x:b.x-r,y:b.y-r,width:r*2,height:r*2))
        }; return path
    }
    public static func fillRule(_ stroke:JSON)->CGPathFillRule {
        let prefix=stroke["vectorPath"].string.prefix(8).filter{!$0.isWhitespace}
        return prefix.hasPrefix("F0") ? .evenOdd : .winding
    }
    /// Source migration emits SVG/WPF M/L/C/Q/Z paths; repeated coordinate groups are significant.
    public static func parse(_ value: String) throws -> CGPath {
        let regex=try NSRegularExpression(pattern:"[A-Za-z]|[-+]?(?:[0-9]*\\.[0-9]+|[0-9]+\\.?[0-9]*)(?:[eE][-+]?[0-9]+)?")
        let range=NSRange(value.startIndex...,in:value)
        let tokens=regex.matches(in:value,range:range).map { String(value[Range($0.range,in:value)!]) }
        let path=CGMutablePath(); var i=0,command="", current=CGPoint.zero,start=CGPoint.zero
        func read() throws -> Double {
            guard i<tokens.count,let n=Double(tokens[i]),n.isFinite else { throw MojianError.invalid("无效的矢量轮廓") }; i+=1; return n
        }
        func point(relative: Bool) throws -> CGPoint { let x=try read(),y=try read(); return CGPoint(x:x+(relative ? current.x : 0),y:y+(relative ? current.y : 0)) }
        while i<tokens.count {
            if tokens[i].first?.isLetter == true { command=tokens[i]; i+=1 }
            let relative=command==command.lowercased()
            switch command.uppercased() {
            case "F": _=try read()
            case "M": let p=try point(relative:relative); path.move(to:p); current=p; start=p; command=relative ? "l" : "L"
            case "L": let p=try point(relative:relative); path.addLine(to:p); current=p
            case "H": current.x=try read()+(relative ? current.x : 0); path.addLine(to:current)
            case "V": current.y=try read()+(relative ? current.y : 0); path.addLine(to:current)
            case "C": let a=try point(relative:relative),b=try point(relative:relative),p=try point(relative:relative); path.addCurve(to:p,control1:a,control2:b); current=p
            case "Q": let a=try point(relative:relative),p=try point(relative:relative); path.addQuadCurve(to:p,control:a); current=p
            case "Z": path.closeSubpath(); current=start; command=""
            default: throw MojianError.invalid("不支持的矢量路径指令：\(command)")
            }
        }; return path
    }
}
