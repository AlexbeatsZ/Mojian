import UIKit
import SwiftUI
import UniformTypeIdentifiers
import QuickLook
import PDFKit
import MojianCore

extension WorkspaceController {
    var insertionPoint:CGPoint { canvas.viewport.document(CGPoint(x:40,y:100)) }
    func addObject(_ item:JSON) { session.mutate{$0.objects.append(item)};canvas.selected=[item["id"].string];canvas.refresh() }
    func insertTag(kind:Int) { let p=insertionPoint;session.mutate{$0.tags.append(.object(["id":.string(JSON.id("tag")),"kind":.number(Double(kind)),"documentX":.number(p.x),"documentY":.number(p.y),"scale":.number(1)]))} }
    func insertTable() {
        let a=UIAlertController(title:"插入表格",message:"行和列",preferredStyle:.alert);a.addTextField{$0.text="3";$0.keyboardType = .numberPad};a.addTextField{$0.text="3";$0.keyboardType = .numberPad}
        a.addAction(UIAlertAction(title:"取消",style:.cancel));a.addAction(UIAlertAction(title:"插入",style:.default){[weak self] _ in guard let self else{return};let rows=min(50,max(1,Int(a.textFields?[0].text ?? "") ?? 3)),columns=min(20,max(1,Int(a.textFields?[1].text ?? "") ?? 3)),p=self.insertionPoint
            self.addObject(.object(["id":.string(JSON.id("table")),"kind":.number(0),"x":.number(p.x),"y":.number(p.y),"width":.number(Double(columns)*90),"height":.number(Double(rows)*36),"rows":.number(Double(rows)),"columns":.number(Double(columns)),"cells":.array(Array(repeating:.string(""),count:rows*columns))]))})
        present(a,animated:true)
    }
    func insertSticker() {
        let a=UIAlertController(title:"贴纸",message:nil,preferredStyle:.actionSheet)
        for value in ["⭐️","✅","📌","💡","❤️"] {a.addAction(UIAlertAction(title:value,style:.default){[weak self] _ in guard let self else{return};let render=UIGraphicsImageRenderer(size:CGSize(width:256,height:256));let image=render.image{_ in (value as NSString).draw(in:CGRect(x:0,y:0,width:256,height:256),withAttributes:[.font:UIFont.systemFont(ofSize:220)])};if let data=image.pngData(){self.insertImage(data,name:"sticker.png",kind:5)}})}
        a.addAction(UIAlertAction(title:"从文件导入贴纸",style:.default){[weak self] _ in self?.pick("image",types:[.image])});a.addAction(UIAlertAction(title:"取消",style:.cancel));anchor(a);present(a,animated:true)
    }
    func insertLink() {
        let picker=LinkPicker(notebooks:session.notebooks){[weak self] n,s,p in guard let self else{return};self.dismiss(animated:true);let at=self.insertionPoint
            self.addObject(.object(["id":.string(JSON.id("link")),"kind":.number(6),"x":.number(at.x),"y":.number(at.y),"width":.number(280),"height":.number(56),"displayName":.string(p.title),"targetNotebookId":.string(n.id),"targetSectionId":.string(s.id),"targetPageId":.string(p.id)]))}
        present(UIHostingController(rootView:picker),animated:true)
    }
    func pick(_ purpose:String,types:[UTType]) {
        commitEditor();pickerPurpose=purpose;let picker=UIDocumentPickerViewController(forOpeningContentTypes:types,asCopy:true);picker.delegate=self;picker.allowsMultipleSelection=purpose=="image";present(picker,animated:true)
    }
    func documentPicker(_ controller:UIDocumentPickerViewController,didPickDocumentsAt urls:[URL]) {
        for url in urls {
            switch pickerPurpose {
            case "package":importURL(url)
            case "pdf-pages","printout":importPDF(url,asPages:pickerPurpose=="pdf-pages")
            case "image":do{insertImage(try Data(contentsOf:url),name:url.lastPathComponent)}catch{showError(error)}
            default:insertAttachment(url)
            }
        }
    }
    func importURL(_ url:URL) {
        let access=url.startAccessingSecurityScopedResource();defer{if access{url.stopAccessingSecurityScopedResource()}}
        do {session.flush();let notebook=try NotebookArchive.importNotebook(from:url,store:session.store);try session.appendImported(notebook);canvas.refresh(reset:true)}catch{showError(error)}
    }
    func insertImage(_ data:Data,name:String,kind:Int=2) {
        guard let image=UIImage(data:data),let d=session.document else{return}
        do {
            let asset=JSON.id("image")+".png",bytes=image.pngData() ?? data;try session.store.writeAsset(bytes,notebook:d.notebookID,page:d.pageID,name:asset)
            let p=insertionPoint,width=min(480,Double(image.size.width)),height=width*Double(image.size.height/max(1,image.size.width))
            addObject(.object(["id":.string(JSON.id("object")),"kind":.number(Double(kind)),"x":.number(p.x),"y":.number(p.y),"width":.number(width),"height":.number(height),"assetName":.string(asset),"displayName":.string(name),"mimeType":.string("image/png"),"viewInverted":.bool(kind==2 && session.page?.dark==true)]))
        }catch{showError(error)}
    }
    func insertAttachment(_ url:URL) {
        guard let d=session.document else{return}
        do {let bytes=try Data(contentsOf:url),asset=JSON.id("attachment")+"."+url.pathExtension;try session.store.writeAsset(bytes,notebook:d.notebookID,page:d.pageID,name:asset);let p=insertionPoint
            addObject(.object(["id":.string(JSON.id("object")),"kind":.number(1),"x":.number(p.x),"y":.number(p.y),"width":.number(280),"height":.number(64),"assetName":.string(asset),"displayName":.string(url.lastPathComponent),"mimeType":.string(UTType(filenameExtension:url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"),"sizeBytes":.number(Double(bytes.count))]))
        }catch{showError(error)}
    }
    func importPDF(_ url:URL,asPages:Bool) {
        guard let source=CGPDFDocument(url as CFURL),source.numberOfPages>0,let section=session.section else{return}
        do {
            let data=try Data(contentsOf:url),asset=JSON.id("pdf")+".pdf",n=session.notebook
            var owner=session.page?.id ?? "",newPages=[Page](),documents=[PageDocument]()
            if asPages {owner=JSON.id("page")}
            guard !owner.isEmpty else{return};try session.store.writeAsset(data,notebook:n.id,page:owner,name:asset)
            var y=insertionPoint.y
            for index in 1...source.numberOfPages {
                guard let pdf=source.page(at:index) else{continue};let media=pdf.getBoxRect(.mediaBox),width=media.width*96/72,height=media.height*96/72
                let thumbName=JSON.id("pdf-preview")+".png",scale=min(2,2048/max(media.width,media.height))
                let thumb=UIGraphicsImageRenderer(size:CGSize(width:media.width*scale,height:media.height*scale)).image{ ctx in
                    UIColor.white.setFill();ctx.fill(CGRect(x:0,y:0,width:media.width*scale,height:media.height*scale));ctx.cgContext.translateBy(x:0,y:media.height*scale);ctx.cgContext.scaleBy(x:scale,y:-scale);ctx.cgContext.translateBy(x:-media.minX,y:-media.minY);ctx.cgContext.drawPDFPage(pdf)
                }
                let pageID=asPages ? (index==1 ? owner : JSON.id("page")) : owner
                try session.store.writeAsset(thumb.pngData()!,notebook:n.id,page:pageID,name:thumbName)
                // Each page owns its original PDF too, so page-level exchange remains self-contained.
                if pageID != owner {try session.store.writeAsset(data,notebook:n.id,page:pageID,name:asset)}
                let obj:JSON = .object(["id":.string(JSON.id("pdf-object")),"kind":.number(3),"x":.number(asPages ? 0 : insertionPoint.x),"y":.number(asPages ? 0 : y),"width":.number(width),"height":.number(height),"assetName":.string(thumbName),"sourceAssetName":.string(asset),"sourcePageNumber":.number(Double(index)),"isPageBackground":.bool(asPages),"displayName":.string(url.lastPathComponent),"mimeType":.string("image/png"),"viewInverted":.bool(!asPages && session.page?.dark==true)])
                if asPages {
                    let p=Page(.object(["id":.string(pageID),"title":.string("\(url.deletingPathExtension().lastPathComponent) · \(index)"),"kind":.string("PDF")]))
                    var v=Page.defaultView;v["paperSize"] = .string("PDF");v["darkBackground"] = .bool(false);v["backgroundFormat"] = .string("BLANK");v["pageTitleVisible"] = .bool(false);v["customDocumentWidth"] = .number(width);v["customDocumentHeight"] = .number(height);p.view=v
                    p.json["pdf"] = .object(["sourceOwnerPageId":.string(pageID),"sourceAssetName":.string(asset),"sourceDocumentName":.string(url.lastPathComponent),"pageNumber":.number(Double(index)),"pageCount":.number(Double(source.numberOfPages)),"widthPoints":.number(media.width),"heightPoints":.number(media.height)])
                    var d=PageDocument(notebook:n.id,page:pageID);d.objects=[obj];newPages.append(p);documents.append(d)
                }else{session.mutate{$0.objects.append(obj)};y+=height+24}
            }
            if asPages {for d in documents{session.store.savePage(d)};session.store.flush();section.pages+=newPages;try session.select(notebook:n,section:section,page:newPages.first);canvas.refresh(reset:true)}
        }catch{showError(error)}
    }
    func editContent(category:String,item:JSON?) {
        if category=="newText",let item {
            commitEditor();let text=UITextView(frame:CGRect(origin:canvas.viewport.screen(CGPoint(x:item["x"].number(),y:item["y"].number())),size:CGSize(width:min(260,canvas.bounds.width-20),height:80)))
            text.font = .systemFont(ofSize:16);text.textColor=session.page?.dark==true ? .white : .black;text.backgroundColor = .clear;text.layer.borderColor=Theme.accent.cgColor;text.layer.borderWidth=1;text.delegate=self
            editor=text;editingBefore=session.document
            editingItem = .object(["id":.string(JSON.id("text")),"x":item["x"],"y":item["y"],"width":.number(320),"height":.number(80),"fontSize":.number(16),"text":.string("")]);canvas.addSubview(text)
            // First blank touch creates an uncommitted editor. The next touch requests the keyboard.
        }else if category=="textItems",let item {
            commitEditor();editingItem=item;editingBefore=session.document
            let r=Geometry.bounds(item,category:category),text=UITextView(frame:CGRect(origin:canvas.viewport.screen(r.origin),size:CGSize(width:r.width*canvas.viewport.scale,height:max(80,r.height*canvas.viewport.scale))))
            text.font = .systemFont(ofSize:item["fontSize"].number(16)*canvas.viewport.scale);text.text=item["text"].string;text.textColor=session.page?.dark==true ? .white : .black;text.backgroundColor=canvas.renderer.paperColor(session.page!,printing:false);text.delegate=self;editor=text;canvas.addSubview(text);text.becomeFirstResponder()
        }else if category=="objects",let item {
            let kind=Int(item["kind"].number())
            if kind==0 {present(UIHostingController(rootView:TableEditor(item:item){[weak self] updated in self?.session.mutate{d in d.objects=d.objects.map{$0["id"]==updated["id"] ? updated : $0}}}),animated:true)}
            else if kind==6 {
                guard let n=session.notebooks.first(where:{$0.id==item["targetNotebookId"].string}),let s=n.allSections.first(where:{$0.id==item["targetSectionId"].string}),let p=s.pages.first(where:{$0.id==item["targetPageId"].string}) else{showError(MojianError.invalid("链接目标已不存在"));return}
                do{try session.select(notebook:n,section:s,page:p);canvas.refresh(reset:true)}catch{showError(error)}
            }else if [1,4].contains(kind),let d=session.document,let url=canvas.renderer.assetURL(item["assetName"].string,d:d) {
                present(UIDocumentInteractionControllerHolder(url:url),animated:true)
            }
        }
    }
    func textViewDidChange(_ textView:UITextView) {
        guard var item=editingItem, !textView.text.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty else{return}
        item["text"] = .string(textView.text);item["height"] = .number(max(60,Double(textView.sizeThatFits(CGSize(width:textView.bounds.width,height:.greatestFiniteMagnitude)).height)/canvas.viewport.scale));editingItem=item
        guard var d=session.document else{return};if let i=d.texts.firstIndex(where:{$0["id"]==item["id"]}){d.texts[i]=item}else{d.texts.append(item)};session.document=d;session.save();canvas.refresh()
    }
    func textViewDidEndEditing(_ textView:UITextView) { commitEditor() }
    func commitEditor() {
        guard let editor else{return};self.editor=nil;editor.delegate=nil;editor.resignFirstResponder();editor.removeFromSuperview()
        if let before=editingBefore {session.commit(before)};editingBefore=nil;editingItem=nil
    }
    func formatText(_ property:String) {let selected=canvas.selected;session.mutate{d in d.texts=d.texts.map{t in var t=t;if selected.contains(t["id"].string){t[property] = .bool(!t[property].bool())};return t}}}
    func showCanvasMenu(at point:CGPoint) {
        let a=UIAlertController(title:nil,message:nil,preferredStyle:.actionSheet)
        for (title,run) in [("复制（原格式）",{self.copySelection()}),("剪切",{self.copySelection();self.canvas.deleteSelection()}),("粘贴",{self.paste()}),("复制为 PNG",{self.copyPNG()}),("复制为 SVG",{self.copySVG()}),("删除所选",{self.canvas.deleteSelection()})] {
            a.addAction(UIAlertAction(title:title,style:.default){_ in run()})
        }
        if let item=session.document?.objects.first(where:{canvas.selected.contains($0["id"].string)}),[2,3,5].contains(Int(item["kind"].number())) {
            for (title,key,value) in [("反色","viewInverted",JSON.bool(!item["viewInverted"].bool())),("顺时针旋转","rotationQuarterTurns",JSON.number((item["rotationQuarterTurns"].number()+1).truncatingRemainder(dividingBy:4))),("水平翻转","flipHorizontal",JSON.bool(!item["flipHorizontal"].bool())),("垂直翻转","flipVertical",JSON.bool(!item["flipVertical"].bool()))] {
                a.addAction(UIAlertAction(title:title,style:.default){[weak self] _ in self?.session.mutate{d in d.objects=d.objects.map{v in var v=v;if v["id"]==item["id"]{v[key]=value};return v}}})
            }
        }
        a.addAction(UIAlertAction(title:"取消",style:.cancel));a.popoverPresentationController?.sourceView=canvas;a.popoverPresentationController?.sourceRect=CGRect(origin:point,size:CGSize(width:1,height:1));present(a,animated:true)
    }
}
private struct TableEditor:View {
    @State var item:JSON
    var save:(JSON)->Void
    @Environment(\.dismiss) var dismiss
    var body:some View {
        NavigationStack{Form{
            ForEach(0..<item["cells"].array.count,id:\.self){i in TextField("\(i/Int(item["columns"].number(1))+1) 行 · \(i%Int(item["columns"].number(1))+1) 列",text:Binding(get:{item["cells"].array[i].string},set:{value in var a=item["cells"].array;a[i] = .string(value);item["cells"] = .array(a)}))}
        }.navigationTitle("表格").toolbar{ToolbarItem(placement:.confirmationAction){Button("完成"){save(item);dismiss()}}}}.preferredColorScheme(.dark)
    }
}
private struct LinkPicker:View {
    let notebooks:[Notebook],choose:(Notebook,MojianCore.Section,Page)->Void
    @Environment(\.dismiss) var dismiss
    var body:some View {NavigationStack{List{ForEach(notebooks,id:\.id){n in SwiftUI.Section(n.title){ForEach(n.allSections,id:\.id){s in ForEach(s.pages,id:\.id){p in Button("\(s.title) / \(p.title)"){choose(n,s,p)}}}}}}.navigationTitle("链接到页面").toolbar{ToolbarItem(placement:.cancellationAction){Button("取消"){dismiss()}}}}.preferredColorScheme(.dark)}
}
private final class UIDocumentInteractionControllerHolder:UIViewController,QLPreviewControllerDataSource {
    let url:URL
    init(url:URL){self.url=url;super.init(nibName:nil,bundle:nil)}
    required init?(coder:NSCoder){fatalError()}
    override func viewDidLoad(){super.viewDidLoad();let preview=QLPreviewController();preview.dataSource=self;addChild(preview);view.addSubview(preview.view);preview.view.frame=view.bounds;preview.view.autoresizingMask=[.flexibleWidth,.flexibleHeight];preview.didMove(toParent:self)}
    func numberOfPreviewItems(in controller:QLPreviewController)->Int{1}
    func previewController(_ controller:QLPreviewController,previewItemAt index:Int)->QLPreviewItem{url as NSURL}
}
