import UIKit
import UniformTypeIdentifiers
import MojianCore

extension WorkspaceController {
    func showFileMenu() {
        let a=UIAlertController(title:"文件",message:session.notebook.title,preferredStyle:.actionSheet)
        a.addAction(UIAlertAction(title:"导入墨笺笔记本 (.mjn)",style:.default){[weak self] _ in self?.pick("package",types:[UTType(importedAs:"com.meta.mojian.notebook"),.zip])})
        a.addAction(UIAlertAction(title:"导出笔记本 (.mjn)",style:.default){[weak self] _ in self?.exportNotebook()})
        a.addAction(UIAlertAction(title:"导出当前页 PDF",style:.default){[weak self] _ in self?.exportPDF(printing:false)})
        a.addAction(UIAlertAction(title:"导出当前页 PNG",style:.default){[weak self] _ in self?.exportPNG()})
        a.addAction(UIAlertAction(title:"打印",style:.default){[weak self] _ in self?.printOptions()})
        a.addAction(UIAlertAction(title:"选项",style:.default){[weak self] _ in self?.showSettings()});a.addAction(UIAlertAction(title:"取消",style:.cancel));anchor(a);present(a,animated:true)
    }
    func outputURL(_ extensionName:String,title:String?=nil)->URL {
        let directory=FileManager.default.temporaryDirectory.appendingPathComponent("MojianExports/\(UUID().uuidString)",isDirectory:true)
        try? FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        let title=title ?? (extensionName=="mjn" ? session.notebook.title : session.page?.title ?? "墨笺")
        let name=String(title.map{ "/\\:*?\"<>|".contains($0) ? "_" : $0 }.prefix(80))
        return directory.appendingPathComponent("\(name.isEmpty ? "墨笺" : name).\(extensionName)")
    }
    func share(_ item:Any) {
        let vc=UIActivityViewController(activityItems:[item],applicationActivities:nil);vc.popoverPresentationController?.sourceView=view;vc.popoverPresentationController?.sourceRect=CGRect(x:view.bounds.midX,y:80,width:1,height:1);present(vc,animated:true)
    }
    func exportNotebook() {do{commitEditor();session.flush();let url=outputURL("mjn");try NotebookArchive.export(session.notebook,store:session.store,to:url);share(url)}catch{showError(error)} }
    func pdfData(printing:Bool,hidePattern:Bool=false)throws->Data {
        guard let p=session.page,let d=session.document else{throw MojianError.invalid("请先打开一个页面")}
        let sheets=ExportPlan.sheets(page:p,document:d),format=UIGraphicsPDFRendererFormat();format.documentInfo=[kCGPDFContextTitle as String:p.title,kCGPDFContextCreator as String:"墨笺"]
        let renderer=UIGraphicsPDFRenderer(bounds:CGRect(x:0,y:0,width:sheets[0].widthPoints,height:sheets[0].heightPoints),format:format)
        return renderer.pdfData { context in
            for sheet in sheets {
                context.beginPage(withBounds:CGRect(x:0,y:0,width:sheet.widthPoints,height:sheet.heightPoints),pageInfo:[:])
                let c=context.cgContext;c.setFillColor(canvas.renderer.paperColor(p,printing:printing).cgColor);c.fill(CGRect(x:0,y:0,width:sheet.widthPoints,height:sheet.heightPoints))
                c.saveGState();c.scaleBy(x:sheet.scale,y:sheet.scale);c.translateBy(x:-sheet.source.minX,y:-sheet.source.minY);c.clip(to:sheet.source)
                canvas.renderer.drawBackground(p,context:c,visible:sheet.source,printing:printing,hidePattern:hidePattern);canvas.renderer.draw(d,page:p,context:c,visible:sheet.source,printing:printing);c.restoreGState()
            }
        }
    }
    func exportPDF(printing:Bool) {do{commitEditor();let url=outputURL("pdf");try pdfData(printing:printing).write(to:url,options:.atomic);share(url)}catch{showError(error)} }
    func printOptions() {
        let a=UIAlertController(title:"打印",message:session.page?.dark==true ? "深色纸张和白色书写会转换为白纸黑字，原始页面保持不变。" : nil,preferredStyle:.actionSheet)
        a.addAction(UIAlertAction(title:"打印（保留背景格式）",style:.default){[weak self] _ in self?.printPage(hidePattern:false)})
        a.addAction(UIAlertAction(title:"打印（隐藏背景格式）",style:.default){[weak self] _ in self?.printPage(hidePattern:true)})
        a.addAction(UIAlertAction(title:"取消",style:.cancel));anchor(a);present(a,animated:true)
    }
    func printPage(hidePattern:Bool) {
        do{commitEditor();let controller=UIPrintInteractionController.shared,info=UIPrintInfo(dictionary:nil);info.jobName=session.page?.title ?? "墨笺";info.outputType = .general;controller.printInfo=info;controller.printingItem=try pdfData(printing:true,hidePattern:hidePattern);controller.present(animated:true,completionHandler:nil)}catch{showError(error)}
    }
    func imageOfSelection(only:Set<String>?) -> UIImage? {
        guard let d=session.document,let p=session.page else{return nil}
        let b=only==nil ? (d.contentBounds.isNull ? CGRect(x:0,y:0,width:p.width,height:p.height) : d.contentBounds.insetBy(dx:-6*unitsPerMM,dy:-6*unitsPerMM)) : canvas.selectionBounds().insetBy(dx:-2,dy:-2)
        guard !b.isNull,b.width>0,b.height>0 else{return nil}
        let scale=min(2,min(8192/max(b.width,b.height),sqrt(16000000/(b.width*b.height)))),format=UIGraphicsImageRendererFormat();format.scale=1;format.opaque=only==nil
        return UIGraphicsImageRenderer(size:CGSize(width:ceil(b.width*scale),height:ceil(b.height*scale)),format:format).image { ctx in
            ctx.cgContext.scaleBy(x:scale,y:scale);ctx.cgContext.translateBy(x:-b.minX,y:-b.minY)
            if only==nil {ctx.cgContext.setFillColor(canvas.renderer.paperColor(p,printing:false).cgColor);ctx.cgContext.fill(b);canvas.renderer.drawBackground(p,context:ctx.cgContext,visible:b)}
            canvas.renderer.draw(d,page:p,context:ctx.cgContext,visible:b,only:only)
        }
    }
    func exportPNG() {
        guard let d=session.document,let p=session.page else{return};commitEditor()
        do {
            var urls=[URL]()
            for (index,sheet) in ExportPlan.sheets(page:p,document:d).enumerated() {
                let format=UIGraphicsImageRendererFormat();format.scale=1;format.opaque=true
                let image=UIGraphicsImageRenderer(size:CGSize(width:sheet.widthPoints*4,height:sheet.heightPoints*4),format:format).image{ctx in
                    ctx.cgContext.setFillColor(canvas.renderer.paperColor(p,printing:false).cgColor);ctx.cgContext.fill(CGRect(x:0,y:0,width:sheet.widthPoints*4,height:sheet.heightPoints*4))
                    ctx.cgContext.scaleBy(x:sheet.scale*4,y:sheet.scale*4);ctx.cgContext.translateBy(x:-sheet.source.minX,y:-sheet.source.minY);ctx.cgContext.clip(to:sheet.source)
                    canvas.renderer.drawBackground(p,context:ctx.cgContext,visible:sheet.source);canvas.renderer.draw(d,page:p,context:ctx.cgContext,visible:sheet.source)
                }
                let url=outputURL("png",title:"\(p.title) · \(index+1)");try image.pngData()!.write(to:url);urls.append(url)
            }
            let vc=UIActivityViewController(activityItems:urls,applicationActivities:nil);vc.popoverPresentationController?.sourceView=view;vc.popoverPresentationController?.sourceRect=CGRect(x:view.bounds.midX,y:80,width:1,height:1);present(vc,animated:true)
        }catch{showError(error)}
    }
    func copySelection() {
        guard let current=session.document,!canvas.selected.isEmpty else{return}
        var copied=current
        for key in ["strokes","objects","textItems","tags"] {copied.json[key] = .array(current.json[key].array.filter{canvas.selected.contains($0["id"].string)})}
        clipboard=copied;clipboardAssets=[:]
        for object in copied.objects {for key in ["assetName","sourceAssetName"] {let name=object[key].string;if let url=canvas.renderer.assetURL(name,d:current),let data=try? Data(contentsOf:url){clipboardAssets[name]=data}}}
        clipboardToken=UUID().uuidString;UIPasteboard.general.setItems([["com.meta.mojian.selection-token":clipboardToken]],options:[.localOnly:true])
    }
    func paste() {
        if let token=UIPasteboard.general.value(forPasteboardType:"com.meta.mojian.selection-token") as? String,token==clipboardToken,var copied=clipboard,let target=session.document {
            do {
                var assetNames=[String:String]()
                for (old,bytes) in clipboardAssets {let name=JSON.id("pasted")+"."+(old as NSString).pathExtension;assetNames[old]=name;try session.store.writeAsset(bytes,notebook:target.notebookID,page:target.pageID,name:name)}
                var ids=Set<String>()
                for key in ["strokes","objects","textItems","tags"] {copied.json[key] = .array(copied.json[key].array.map{item in var item=item;item["id"] = .string(JSON.id("pasted"));ids.insert(item["id"].string);item["layerId"] = .string("");item["zOrder"] = .number(0)
                    for field in ["assetName","sourceAssetName"] {if let name=assetNames[item[field].string]{item[field] = .string(name)}};item["isPageBackground"] = .bool(false);return item})}
                let b=copied.contentBounds;copied.transform(ids:ids,from:b,to:b.offsetBy(dx:24,dy:24))
                session.mutate{d in for key in ["strokes","objects","textItems","tags"] {d.json[key] = .array(d.json[key].array+copied.json[key].array)}};canvas.selected=ids;canvas.refresh()
            }catch{showError(error)}
        }else if let image=UIPasteboard.general.image,let data=image.pngData(){insertImage(data,name:"粘贴的图片.png")}
        else if let text=UIPasteboard.general.string,!text.isEmpty {let p=insertionPoint;session.mutate{$0.texts.append(.object(["id":.string(JSON.id("text")),"x":.number(p.x),"y":.number(p.y),"width":.number(320),"height":.number(100),"fontSize":.number(16),"text":.string(text)]))}}
    }
    func copyPNG() {guard let image=imageOfSelection(only:canvas.selected),!canvas.selected.isEmpty else{return};clipboard=nil;clipboardToken="";UIPasteboard.general.image=image}
    func copySVG() {
        guard let d=session.document,let p=session.page,!canvas.selected.isEmpty else{return};let bounds=canvas.selectionBounds().insetBy(dx:-2,dy:-2)
        func number(_ n:CGFloat)->String{String(format:"%.4f",Double(n))}
        var content=""
        for (category,stroke) in canvas.renderer.ordered(d) where canvas.selected.contains(stroke["id"].string) {
            if category != "strokes" {
                if let png=imageOfSelection(only:[stroke["id"].string])?.pngData(){content+="<image x=\"\(bounds.minX)\" y=\"\(bounds.minY)\" width=\"\(bounds.width)\" height=\"\(bounds.height)\" href=\"data:image/png;base64,\(png.base64EncodedString())\"/>"}
                continue
            }
            var path="";canvas.renderer.cachedPath(stroke).applyWithBlock{element in
                let e=element.pointee
                switch e.type {case .moveToPoint:path+="M \(number(e.points[0].x)) \(number(e.points[0].y)) "
                case .addLineToPoint:path+="L \(number(e.points[0].x)) \(number(e.points[0].y)) "
                case .addQuadCurveToPoint:path+="Q \(number(e.points[0].x)) \(number(e.points[0].y)) \(number(e.points[1].x)) \(number(e.points[1].y)) "
                case .addCurveToPoint:path+="C \(number(e.points[0].x)) \(number(e.points[0].y)) \(number(e.points[1].x)) \(number(e.points[1].y)) \(number(e.points[2].x)) \(number(e.points[2].y)) "
                case .closeSubpath:path+="Z ";@unknown default:break}
            }
            var r:CGFloat=0,g:CGFloat=0,b:CGFloat=0,a:CGFloat=0;canvas.renderer.inkColor(stroke,dark:p.dark).getRed(&r,green:&g,blue:&b,alpha:&a)
            content+="<path d=\"\(path)\" fill-rule=\"\(InkPath.fillRule(stroke) == .evenOdd ? "evenodd" : "nonzero")\" fill=\"\(String(format:"#%02X%02X%02X",Int(r*255),Int(g*255),Int(b*255)))\" opacity=\"\(stroke["opacityPercent"].number(100)/100)\"/>"
        }
        let svg="<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"\(bounds.minX) \(bounds.minY) \(bounds.width) \(bounds.height)\">\(content)</svg>"
        clipboard=nil;clipboardToken="";UIPasteboard.general.setItems([["public.svg-image":Data(svg.utf8),"public.utf8-plain-text":Data(svg.utf8)]])
    }
}
