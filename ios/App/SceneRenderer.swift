import UIKit
import PDFKit
import CoreImage
import MojianCore

enum Theme {
    static let chrome=UIColor(hex:"#191919"), rail=UIColor(hex:"#111111"), pane=UIColor(hex:"#242424")
    static let accent=UIColor(hex:"#BAA3E3"), paper=UIColor(hex:"#1B1B1B"), workspace=UIColor(hex:"#0A0A0A")
}
extension UIColor {
    convenience init(hex: String) {
        let h=hex.trimmingCharacters(in:CharacterSet(charactersIn:"#")), n=UInt64(h,radix:16) ?? 0
        self.init(red:CGFloat((n>>16)&255)/255,green:CGFloat((n>>8)&255)/255,blue:CGFloat(n&255)/255,alpha:h.count==8 ? CGFloat((n>>24)&255)/255 : 1)
    }
    var nearWhite: Bool { var r:CGFloat=0,g:CGFloat=0,b:CGFloat=0,a:CGFloat=0; getRed(&r,green:&g,blue:&b,alpha:&a); return r>0.85 && g>0.85 && b>0.85 }
}

final class SceneRenderer {
    let store: WorkspaceStore
    private var paths=[String:(JSON,CGPath,CGRect)](), images=[String:UIImage](), pdfs=[String:CGPDFDocument]()
    private let imageContext=CIContext()
    init(store: WorkspaceStore) { self.store=store }
    func inkColor(_ stroke: JSON, dark: Bool, printing: Bool = false) -> UIColor {
        let explicit=stroke["colorHex"].string
        let color = !explicit.isEmpty ? UIColor(hex:explicit) : stroke["color"].number()==1 ? UIColor(hex:"#488CED") : stroke["color"].number()==2 ? UIColor(hex:"#FF4444") : dark ? UIColor(white:0.94,alpha:1) : UIColor(hex:"#151515")
        return printing && dark && color.nearWhite ? UIColor.black : color
    }
    func cachedPath(_ stroke: JSON) -> CGPath {
        let id=stroke["id"].string
        // Compare geometry metadata, not every point on each viewport frame. All native transforms
        // increment iosGeometryRevision; reset clears caches before rebinding another page.
        let points=stroke["points"].array
        var key=JSON.object([:])
        for field in ["iosGeometryRevision","vectorPath","vectorTransform","widthMm","pressurePercent","inkFlowPercent","kind","brushProfile"]{key[field]=stroke[field]}
        key["count"] = .number(Double(points.count));key["first"]=points.first ?? .null;key["last"]=points.last ?? .null
        if let cached=paths[id],cached.0==key { return cached.1 }
        let p=InkPath.make(stroke); paths[id]=(key,p,p.boundingBoxOfPath); return p
    }
    func clear(){paths.removeAll();images.removeAll();pdfs.removeAll()}
    func cachedBounds(_ stroke:JSON)->CGRect{_ = cachedPath(stroke);return paths[stroke["id"].string]?.2 ?? .null}
    func trim(to document: PageDocument) {
        let ids=Set(document.strokes.map{$0["id"].string}); paths=paths.filter{ids.contains($0.key)}
    }
    func paperColor(_ page: Page, printing: Bool) -> UIColor {
        if printing && page.dark { return .white }
        let pairs:[String:(String,String)] = ["WHITE":("#FAFAFA","#1B1B1B"),"SAND":("#F3E9D2","#302A21"),"BLUE":("#E2EDF8","#1D2B38"),"MINT":("#E0F1E7","#1E3028"),"ROSE":("#F4E2E8","#352329")]
        let colors=pairs[page.view["pageColor"].string] ?? pairs["WHITE"]!; return UIColor(hex:page.dark ? colors.1 : colors.0)
    }
    func drawBackground(_ page: Page, context c: CGContext, visible: CGRect, printing: Bool = false, hidePattern: Bool = false) {
        let gap=12*unitsPerMM, h=page.height, w=page.width
        let first=page.paper=="A4" ? max(0,Int(visible.minY/(h+gap))) : 0
        let last=page.paper=="A4" ? min(page.sheetCount-1,max(0,Int(visible.maxY/(h+gap)))) : 0
        guard first<=last else { return }
        for index in first...last {
            let rect=CGRect(x:0,y:Double(index)*(h+gap),width:w,height:h)
            c.saveGState(); c.setFillColor(paperColor(page,printing:printing).cgColor); c.fill(rect); c.clip(to:rect)
            if !hidePattern, let format=Background.restore(page.view["backgroundFormat"].string),format != .blank {
                let clip=visible.intersection(rect), step=format.spacing
                c.setStrokeColor(UIColor(hex:page.dark && !printing ? "#344652" : "#CFDDE6").cgColor); c.setLineWidth(0.55)
                // Skip subpixel grid at high zoom-out; grid work is always viewport bounded.
                let x0=floor(max(0,clip.minX)/step)*step,y0=rect.minY+floor((max(rect.minY,clip.minY)-rect.minY)/step)*step
                if format.rawValue.contains("DOTS") {
                    c.setFillColor(UIColor(hex:page.dark && !printing ? "#415868" : "#B7CBD5").cgColor)
                    for y in stride(from:y0,through:clip.maxY,by:step) { for x in stride(from:x0,through:clip.maxX,by:step) { c.fillEllipse(in:CGRect(x:x-0.8,y:y-0.8,width:1.6,height:1.6)) } }
                } else {
                    for y in stride(from:y0,through:clip.maxY,by:step) { c.move(to:CGPoint(x:clip.minX,y:y)); c.addLine(to:CGPoint(x:clip.maxX,y:y)) }
                    if format.rawValue.contains("GRID") { for x in stride(from:x0,through:clip.maxX,by:step) { c.move(to:CGPoint(x:x,y:clip.minY)); c.addLine(to:CGPoint(x:x,y:clip.maxY)) } }
                    if format.showsMargin { c.move(to:CGPoint(x:64,y:rect.minY)); c.addLine(to:CGPoint(x:64,y:rect.maxY)) }
                    if format.rawValue.contains("TWO_COLUMN") { c.move(to:CGPoint(x:w/2,y:rect.minY)); c.addLine(to:CGPoint(x:w/2,y:rect.maxY)) }; c.strokePath()
                }
            }
            c.restoreGState()
        }
        if page.view["pageTitleVisible"].bool(true) {
            let color=page.dark && !printing ? UIColor(white:0.9,alpha:1) : .black
            (page.title as NSString).draw(in:CGRect(x:24,y:20,width:w-48,height:44),withAttributes:[.font:UIFont.systemFont(ofSize:24,weight:.semibold),.foregroundColor:color])
        }
    }
    func ordered(_ d: PageDocument) -> [(String,JSON)] {
        let layers=d.json["layers"].array
        func order(_ item:JSON)->Double { if item["isPageBackground"].bool() { return -Double.greatestFiniteMagnitude }; return layers.first{$0["id"]==item["layerId"]}? ["order"].number() ?? Double.greatestFiniteMagnitude }
        let items=d.objects.map{("objects",$0)}+d.strokes.map{("strokes",$0)}+d.texts.map{("textItems",$0)}
        return items.enumerated().filter { entry in layers.first{$0["id"]==entry.element.1["layerId"]}? ["visible"].bool(true) ?? true }.sorted {
            let l=order($0.element.1),r=order($1.element.1)
            if l != r { return l<r }; let z0=$0.element.1["zOrder"].number(),z1=$1.element.1["zOrder"].number()
            return z0==z1 ? $0.offset<$1.offset : z0<z1
        }.map(\.element)+d.tags.map{("tags",$0)}
    }
    func draw(_ d: PageDocument, page: Page, context c: CGContext, visible: CGRect, printing: Bool = false, only: Set<String>? = nil) {
        for (key,item) in ordered(d) {
            if let only,!only.contains(item["id"].string) { continue }
            guard (key=="strokes" ? cachedBounds(item) : Geometry.bounds(item,category:key)).intersects(visible) else { continue }
            c.saveGState()
            if key=="strokes" {
                c.setFillColor(inkColor(item,dark:page.dark,printing:printing).cgColor); c.setAlpha(item["opacityPercent"].number(100)/100)
                c.addPath(cachedPath(item)); c.fillPath(using:InkPath.fillRule(item))
            } else if key=="textItems" {
                let size=item["fontSize"].number(16), paragraph=NSMutableParagraphStyle()
                paragraph.alignment=item["alignment"].string=="Center" ? .center : item["alignment"].string=="Right" ? .right : .left
                paragraph.lineSpacing=max(0,(item["lineSpacing"].number(1)-1)*size)
                var font=UIFont.systemFont(ofSize:size,weight:item["bold"].bool() ? .bold : .regular)
                if item["italic"].bool(), let descriptor=font.fontDescriptor.withSymbolicTraits(.traitItalic) { font=UIFont(descriptor:descriptor,size:size) }
                var color=inkColor(item,dark:page.dark,printing:printing); if !item["foregroundHex"].string.isEmpty { color=UIColor(hex:item["foregroundHex"].string) }
                if printing && page.dark && color.nearWhite { color = .black }
                var attrs:[NSAttributedString.Key:Any]=[.font:font,.foregroundColor:color,.paragraphStyle:paragraph]
                if item["underline"].bool() { attrs[.underlineStyle]=NSUnderlineStyle.single.rawValue }
                if item["strikethrough"].bool() { attrs[.strikethroughStyle]=NSUnderlineStyle.single.rawValue }
                (item["text"].string as NSString).draw(in:Geometry.bounds(item,category:key),withAttributes:attrs)
            } else if key=="tags" {
                let rect=Geometry.bounds(item,category:key); ((item["kind"].number()==1 ? "?" : "!") as NSString).draw(in:rect,withAttributes:[.font:UIFont.systemFont(ofSize:rect.height,weight:.bold),.foregroundColor:UIColor.red])
            } else { drawObject(item,d:d,page:page,context:c,printing:printing) }
            c.restoreGState()
        }
    }
    func assetURL(_ name: String, d: PageDocument, owner: String? = nil) -> URL? {
        guard (try? WorkspaceStore.safe(name)) != nil,let dir=try? store.assets(notebook:d.notebookID,page:owner ?? d.pageID) else { return nil }
        return dir.appendingPathComponent(name)
    }
    func drawObject(_ item: JSON, d: PageDocument, page: Page, context c: CGContext, printing: Bool) {
        let rect=Geometry.bounds(item,category:"objects"),kind=Int(item["kind"].number(2))
        if [2,3,5].contains(kind) {
            let originalName=item["sourceAssetName"].string, name=item["assetName"].string
            let owner=page.json["pdf"]["sourceOwnerPageId"].string
            let pdfURL=assetURL(originalName,d:d,owner:owner.isEmpty ? nil : owner)
            let inverted=printing && page.dark ? false : item["viewInverted"].bool()
            c.translateBy(x:rect.midX,y:rect.midY); c.rotate(by:Double(Int(item["rotationQuarterTurns"].number())%4)*Double.pi/2)
            c.scaleBy(x:item["flipHorizontal"].bool() ? -1 : 1,y:item["flipVertical"].bool() ? -1 : 1)
            let r=CGRect(x:-rect.width/2,y:-rect.height/2,width:rect.width,height:rect.height)
            let number=item["sourcePageNumber"].number()
            if !inverted,let url=pdfURL,number>=1 {
                let pdf=pdfs[url.path] ?? CGPDFDocument(url as CFURL); if let pdf { pdfs[url.path]=pdf }
                if let source=pdf?.page(at:Int(number)) {
                    c.translateBy(x:r.minX,y:r.maxY); c.scaleBy(x:r.width/source.getBoxRect(.mediaBox).width,y:-r.height/source.getBoxRect(.mediaBox).height)
                    c.translateBy(x:-source.getBoxRect(.mediaBox).minX,y:-source.getBoxRect(.mediaBox).minY); c.drawPDFPage(source); return
                }
            }
            if let url=assetURL(name,d:d), let image=image(url,inverted:inverted) { image.draw(in:r) }
            else { c.setFillColor(UIColor.darkGray.cgColor); c.fill(r); ("缺少图片" as NSString).draw(in:r,withAttributes:[.foregroundColor:UIColor.white]) }
        } else if kind==0 || kind==4 {
            let rows=kind==0 ? max(1,Int(item["rows"].number(3))) : max(1,item["previewRows"].array.count)
            let cols=kind==0 ? max(1,Int(item["columns"].number(3))) : max(1,item["previewRows"].array.first?.array.count ?? 1)
            c.setFillColor((page.dark && !printing ? UIColor(hex:"#242424") : .white).cgColor); c.fill(rect)
            c.setStrokeColor(UIColor.gray.cgColor); c.setLineWidth(0.6)
            for row in 0..<min(200,rows) { for col in 0..<min(50,cols) {
                let cell=CGRect(x:rect.minX+Double(col)*rect.width/Double(cols),y:rect.minY+Double(row)*rect.height/Double(rows),width:rect.width/Double(cols),height:rect.height/Double(rows)); c.stroke(cell)
                let cells=item["cells"].array, preview=item["previewRows"].array
                let text=kind==0 ? (row*cols+col<cells.count ? cells[row*cols+col].string : "") : (row<preview.count && col<preview[row].array.count ? preview[row].array[col].string : "")
                (text as NSString).draw(in:cell.insetBy(dx:4,dy:3),withAttributes:[.font:UIFont.systemFont(ofSize:14),.foregroundColor:page.dark && !printing ? UIColor.white : UIColor.black])
            } }
        } else {
            c.setFillColor((page.dark && !printing ? UIColor(hex:"#343039") : UIColor(hex:"#EEEAF5")).cgColor); c.fill(rect)
            (item["displayName"].string as NSString).draw(in:rect.insetBy(dx:12,dy:12),withAttributes:[.font:UIFont.systemFont(ofSize:16),.foregroundColor:page.dark && !printing ? UIColor.white : UIColor.black])
        }
    }
    func image(_ url: URL, inverted: Bool) -> UIImage? {
        let key=url.path+(inverted ? ":invert" : "")
        if let image=images[key] { return image }; guard let image=UIImage(contentsOfFile:url.path) else { return nil }
        var result=image
        if inverted,let source=CIImage(image:image) {
            // CSS invert(89.4118%) followed by hue-rotate(180deg), matching existing clients.
            let q=1-2*(1-27.0/255),p=1-27.0/255
            let filtered=source.applyingFilter("CIColorMatrix",parameters:[
                "inputRVector":CIVector(x:-0.574*q,y:1.43*q,z:0.144*q,w:0),
                "inputGVector":CIVector(x:0.426*q,y:0.43*q,z:0.144*q,w:0),
                "inputBVector":CIVector(x:0.426*q,y:1.43*q,z:-0.856*q,w:0),"inputBiasVector":CIVector(x:p,y:p,z:p,w:0)])
            if let cg=imageContext.createCGImage(filtered,from:source.extent) { result=UIImage(cgImage:cg) }
        }
        if images.count>64 { images.removeAll() }; images[key]=result; return result
    }
}
