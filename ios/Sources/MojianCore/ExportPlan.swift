import Foundation

public struct OutputSheet: Equatable {
    public var source:CGRect, widthPoints:Double, heightPoints:Double, scale:Double
}
public enum ExportPlan {
    public static func sheets(page:Page,document:PageDocument) -> [OutputSheet] {
        let width=210*unitsPerMM,height=297*unitsPerMM,b=document.contentBounds
        if page.paper=="PDF" {
            return [OutputSheet(source:CGRect(x:0,y:0,width:page.width,height:page.height),widthPoints:page.width*72/96,heightPoints:page.height*72/96,scale:72/96)]
        }
        if page.paper=="A4" {
            let gap=12*unitsPerMM,stride=page.height+gap
            let last=b.isNull ? 0 : min(10000,max(0,Int(b.maxY/stride)))
            return (0...last).map { i in
                let top=Double(i)*stride, gapRect=CGRect(x:0,y:top+page.height,width:page.width,height:gap)
                let gapOccupied = ["strokes","objects","textItems","tags"].contains {key in document.json[key].array.contains{Geometry.bounds($0,category:key).intersects(gapRect)} }
                let source=CGRect(x:0,y:top,width:page.width,height:page.height+(gapOccupied ? gap : 0)),scale=min(width/source.width,height/source.height)*72/96
                return OutputSheet(source:source,widthPoints:width*72/96,heightPoints:height*72/96,scale:scale)
            }
        }
        let crop=b.isNull ? CGRect(x:0,y:0,width:width,height:height) : b.insetBy(dx:-6*unitsPerMM,dy:-6*unitsPerMM)
        let ratio=min(1,width/max(1,crop.width)),sliceHeight=height/ratio
        let count=min(10000,max(1,Int(ceil(crop.height/sliceHeight))))
        return (0..<count).map {i in OutputSheet(source:CGRect(x:crop.minX,y:crop.minY+Double(i)*sliceHeight,width:crop.width,height:min(sliceHeight,crop.maxY-crop.minY-Double(i)*sliceHeight)),widthPoints:width*72/96,heightPoints:height*72/96,scale:ratio*72/96)}
    }
}
