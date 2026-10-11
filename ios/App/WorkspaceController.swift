import UIKit
import SwiftUI
import UniformTypeIdentifiers
import PDFKit
import MojianCore

final class WorkspaceController: UIViewController, UIDocumentPickerDelegate, UITextViewDelegate {
    let session:WorkspaceSession, canvas:NoteCanvas
    let tabs=UISegmentedControl(items:["文件","开始","绘图","插入","视图"]), ribbon=UIScrollView(), tools=UIStackView(), rail=UIStackView()
    let sections:LibraryPanel,pages:LibraryPanel
    var sectionsVisible=true,pagesVisible=true,fullPage=false,secondCanvas:NoteCanvas?
    var pickerPurpose="", editor:UITextView?, editingItem:JSON?, editingBefore:PageDocument?
    var clipboard:PageDocument?, clipboardAssets=[String:Data](), clipboardToken=""
    var temporaryTool:PenTool?
    var lastRibbonTab=2
    init(session:WorkspaceSession) {
        self.session=session; canvas=NoteCanvas(session:session); sections=LibraryPanel(session:session,pages:false); pages=LibraryPanel(session:session,pages:true); super.init(nibName:nil,bundle:nil)
    }
    required init?(coder:NSCoder) { fatalError() }
    override func viewDidLoad() {
        super.viewDidLoad(); view.backgroundColor=Theme.chrome; view.tintColor=Theme.accent
        tabs.selectedSegmentIndex=2; tabs.addTarget(self,action:#selector(tabChanged),for:.valueChanged); tabs.selectedSegmentTintColor=UIColor(hex:"#3C3546"); view.addSubview(tabs)
        ribbon.showsHorizontalScrollIndicator=false; tools.axis = .horizontal; tools.spacing=4; ribbon.addSubview(tools); view.addSubview(ribbon)
        rail.axis = .vertical; rail.alignment = .center; rail.backgroundColor=Theme.rail
        rail.addArrangedSubview(button("books.vertical","笔记本和分区"){[weak self] in self?.toggleNavigation(pages:false)})
        rail.addArrangedSubview(button("sidebar.left","页面列表"){[weak self] in self?.toggleNavigation(pages:true)})
        view.addSubview(rail); view.addSubview(sections); view.addSubview(pages); view.addSubview(canvas)
        for panel in [sections,pages] {
            panel.select = { [weak self] n,s,p in
                guard let self else { return }; self.cancelInput(); self.commitEditor()
                do { try self.session.select(notebook:n,section:s,page:p); self.canvas.refresh(reset:true); self.dismiss(animated:true) } catch { self.showError(error) }
            }
            panel.rename = { [weak self] in self?.rename($0) }; panel.delete = { [weak self] in self?.delete($0) }
        }
        sections.create = { [weak self] in self?.newContainer() }; pages.create = { [weak self] in self?.newPageMenu() }
        canvas.action = { [weak self] in self?.perform($0) }; canvas.edit = { [weak self] in self?.editContent(category:$0,item:$1) }
        canvas.toolFeedback = {[weak self] tool in self?.temporaryTool=tool;self?.refresh()}
        canvas.beginInput = {[weak self] in self?.commitEditor()}
        canvas.contextMenu = { [weak self] in self?.showCanvasMenu(at:$0) }
        session.changed = { [weak self] in self?.refresh() }; session.error = { [weak self] in self?.showError($0) }
        refresh(); canvas.refresh(reset:true)
    }
    var compact:Bool { view.bounds.width<760 }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews(); let safe=view.safeAreaInsets,w=view.bounds.width,top=safe.top, bottom=view.bounds.height-safe.bottom
        tabs.isHidden=fullPage; tabs.frame=CGRect(x:safe.left+8,y:top,width:w-safe.left-safe.right-16,height:32)
        let ribbonY=fullPage ? top+4 : top+34,toolWidth=tools.systemLayoutSizeFitting(UIView.layoutFittingCompressedSize).width
        let ribbonWidth=fullPage ? min(toolWidth,w-safe.left-safe.right-48) : w-safe.left-safe.right-48
        ribbon.frame=CGRect(x:fullPage ? safe.left+(w-safe.left-safe.right-ribbonWidth)/2 : safe.left+40,y:ribbonY,width:ribbonWidth,height:48)
        ribbon.backgroundColor=fullPage ? Theme.chrome : .clear;ribbon.layer.cornerRadius=fullPage ? 8 : 0
        tools.frame=CGRect(x:0,y:0,width:toolWidth,height:48); ribbon.contentSize=tools.bounds.size
        let y=fullPage ? top+56 : top+84
        rail.frame=CGRect(x:safe.left,y:y,width:36,height:96); rail.isHidden=false
        var x=safe.left+36
        let showSections=sectionsVisible && !compact && !fullPage, showPages=pagesVisible && !compact && !fullPage
        sections.isHidden = !showSections; pages.isHidden = !showPages
        if showSections { sections.frame=CGRect(x:x,y:y,width:196,height:bottom-y); x+=196 }
        if showPages { pages.frame=CGRect(x:x,y:y,width:196,height:bottom-y); x+=196 }
        if fullPage { x=safe.left }
        canvas.frame=CGRect(x:x,y:y,width:w-safe.right-x,height:bottom-y)
        if let second=secondCanvas {
            let frame=canvas.frame; canvas.frame.size.width=frame.width/2-1; second.frame=CGRect(x:frame.midX+1,y:y,width:frame.width/2-1,height:frame.height)
        }
        if fullPage { view.bringSubviewToFront(rail); view.bringSubviewToFront(ribbon) }
    }
    func button(_ symbol:String,_ title:String,run:@escaping()->Void)->UIButton {
        let b=UIButton(type:.system); b.setImage(UIImage(systemName:symbol),for:.normal); b.tintColor = .white
        b.accessibilityLabel=title; b.accessibilityIdentifier=title; b.widthAnchor.constraint(equalToConstant:44).isActive=true; b.heightAnchor.constraint(equalToConstant:44).isActive=true
        b.addAction(UIAction{_ in run()},for:.touchUpInside); return b
    }
    func command(_ symbol:String,_ title:String,run:@escaping()->Void) { tools.addArrangedSubview(button(symbol,title,run:run)) }
    func menuButton(_ symbol:String,_ title:String,_ menu:UIMenu) { let b=button(symbol,title){}; b.menu=menu; b.showsMenuAsPrimaryAction=true; tools.addArrangedSubview(b) }
    func refresh() {
        sections.reload(); pages.reload(); canvas.refresh(); secondCanvas?.refresh()
        presentedViewController?.view.subviews.compactMap{$0 as? LibraryPanel}.forEach{$0.reload()}
        tools.arrangedSubviews.forEach{tools.removeArrangedSubview($0);$0.removeFromSuperview()}
        if fullPage || tabs.selectedSegmentIndex==2 {
            for t in PenTool.allCases {
                let b=button(t.symbol,t.title){ [weak self] in guard let self else { return }; self.cancelInput(); if self.session.settings.tool==t { self.showSettings() } else { self.session.choose(t) } }
                if (temporaryTool ?? session.settings.tool)==t { b.backgroundColor=Theme.accent.withAlphaComponent(0.17); b.layer.borderColor=Theme.accent.cgColor; b.layer.borderWidth=1; b.layer.cornerRadius=3 }; tools.addArrangedSubview(b)
            }
            menuButton("circle.lefthalf.filled","笔颜色",UIMenu(children:["黑色","蓝色","红色"].enumerated().map { index,name in UIAction(title:name,state:session.settings.color==index ? .on : .off){[weak self] _ in self?.session.settings.color=index; self?.session.save(); self?.refresh()} }))
            command("arrow.uturn.backward","撤销"){[weak self] in self?.perform(.undo)}
            command("arrow.uturn.forward","重做"){[weak self] in self?.perform(.redo)}
            command("arrow.up.and.down.text.horizontal","插入空白空间"){[weak self] in self?.canvas.insertSpace.toggle()}
        } else if tabs.selectedSegmentIndex==1 {
            command("doc.on.doc","复制原格式"){[weak self] in self?.copySelection()}
            command("doc.on.clipboard","粘贴"){[weak self] in self?.paste()}
            command("scissors","剪切"){[weak self] in self?.copySelection(); self?.canvas.deleteSelection()}
            command("trash","删除所选"){[weak self] in self?.canvas.deleteSelection()}
            command("arrow.uturn.backward","撤销"){[weak self] in self?.perform(.undo)}
            command("arrow.uturn.forward","重做"){[weak self] in self?.perform(.redo)}
            command("bold","加粗"){[weak self] in self?.formatText("bold")}
            command("italic","斜体"){[weak self] in self?.formatText("italic")}
            command("underline","下划线"){[weak self] in self?.formatText("underline")}
            command("exclamationmark.circle","重要标签"){[weak self] in self?.insertTag(kind:0)}
            command("questionmark.circle","问题标签"){[weak self] in self?.insertTag(kind:1)}
        } else if tabs.selectedSegmentIndex==3 {
            command("tablecells","插入表格"){[weak self] in self?.insertTable()}
            command("paperclip","插入附件"){[weak self] in self?.pick("attachment",types:[.item])}
            command("doc.richtext","插入 PDF 打印输出"){[weak self] in self?.pick("printout",types:[.pdf])}
            command("photo","插入图片"){[weak self] in self?.pick("image",types:[.image])}
            command("star.square","贴纸"){[weak self] in self?.insertSticker()}
            command("link","笔记链接"){[weak self] in self?.insertLink()}
        } else {
            menuButton("text.justify","背景格式",UIMenu(children:Background.allCases.map { f in UIAction(title:f.title,state:session.page?.view["backgroundFormat"].string==f.rawValue ? .on : .off){[weak self] _ in self?.changeView("backgroundFormat",.string(f.rawValue))} }))
            menuButton("doc","纸张",UIMenu(children:["无界页面","A4"].enumerated().map { i,label in UIAction(title:label){[weak self] _ in self?.changeView("paperSize",.string(i==0 ? "BOUNDLESS" : "A4"))} }))
            menuButton("paintpalette","页面颜色",UIMenu(children:zip(["WHITE","SAND","BLUE","MINT","ROSE"],["白色","米色","蓝色","绿色","粉色"]).map { color,name in UIAction(title:name){[weak self] _ in self?.changeView("pageColor",.string(color))} }))
            command("circle.lefthalf.filled","浅色或深色纸张"){[weak self] in guard let self else{return}; self.changeView("darkBackground",.bool(!(self.session.page?.dark ?? true)))}
            command("textformat","显示页面标题"){[weak self] in guard let self else{return};self.changeView("pageTitleVisible",.bool(!(self.session.page?.view["pageTitleVisible"].bool(true) ?? true)))}
            menuButton("magnifyingglass","缩放",UIMenu(children:Viewport.presets.map { z in UIAction(title:"\(Int(z*100))%"){[weak self] _ in self?.canvas.setZoom(z)} }))
            command("arrow.left.and.right","适合页宽"){[weak self] in self?.canvas.fitWidth()}
            command("lock","锁定双指缩放"){[weak self] in self?.session.settings.pinchLocked.toggle();self?.session.save()}
            command("rectangle.split.2x1","分屏"){[weak self] in self?.toggleSplit()}
        }
        command(fullPage ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right",fullPage ? "退出全页" : "全页"){[weak self] in self?.perform(.fullPage)}
        command("gearshape","选项"){[weak self] in self?.showSettings()}
        view.setNeedsLayout()
    }
    @objc func tabChanged() { if tabs.selectedSegmentIndex==0 { tabs.selectedSegmentIndex=lastRibbonTab; showFileMenu() } else {lastRibbonTab=tabs.selectedSegmentIndex}; refresh() }
    func cancelInput() { canvas.cancelInput(); secondCanvas?.cancelInput() }
    func perform(_ action:PencilAction) {
        commitEditor(); cancelInput()
        switch action {
        case .eraser:session.choose(session.settings.tool == .eraser ? session.previousTool : .eraser)
        case .previousTool:session.choose(session.previousTool)
        case .pen:session.choose(.pen); case .highlighter:session.choose(.highlighter); case .lasso:session.choose(.lasso); case .hand:session.choose(.hand)
        case .undo:session.undo(); case .redo:session.redo(); case .palette:showSettings()
        case .fullPage:fullPage.toggle(); refresh()
        default:break
        }
    }
    func toggleSplit() {
        if let second=secondCanvas { canvas.viewport=second.viewport; second.removeFromSuperview(); secondCanvas=nil }
        else { let second=NoteCanvas(session:session); second.viewport=canvas.viewport; second.action=canvas.action; second.edit=canvas.edit; view.addSubview(second); secondCanvas=second }
        view.setNeedsLayout()
    }
    func showSettings() { cancelInput(); commitEditor(); present(UIHostingController(rootView:SettingsView(settings:session.settings){[weak self] in self?.session.settings=$0;self?.session.save();self?.refresh()}),animated:true) }
    func showError(_ error:Error) { let a=UIAlertController(title:"操作未完成",message:error.localizedDescription,preferredStyle:.alert); a.addAction(UIAlertAction(title:"好",style:.default)); if presentedViewController==nil { present(a,animated:true) } }
    func prompt(_ title:String,value:String="",complete:@escaping(String)->Void) {
        let a=UIAlertController(title:title,message:nil,preferredStyle:.alert); a.addTextField{$0.text=value}; a.addAction(UIAlertAction(title:"取消",style:.cancel));a.addAction(UIAlertAction(title:"完成",style:.default){_ in let text=a.textFields?.first?.text?.trimmingCharacters(in:.whitespacesAndNewlines) ?? "";if !text.isEmpty {complete(text)}})
        topPresenter.present(a,animated:true)
    }
    var topPresenter:UIViewController { presentedViewController ?? self }
    func confirm(_ title:String,run:@escaping()->Void) {
        let a=UIAlertController(title:title,message:"此操作会删除所选内容及其页面文件。",preferredStyle:.alert);a.addAction(UIAlertAction(title:"取消",style:.cancel));a.addAction(UIAlertAction(title:"删除",style:.destructive){_ in run()});topPresenter.present(a,animated:true)
    }
    func rename(_ row:LibraryPanel.Row) {
        let title:String; switch row {case .notebook(let n):title=n.title;case .section(_,let s,_):title=s.title;case .page(_,_,let p,_):title=p.title}
        prompt("重命名",value:title){[weak self] text in switch row {case .notebook(let n):n.title=text;case .section(_,let s,_):s.title=text;case .page(_,_,let p,_):p.title=text};self?.session.save();self?.refresh()}
    }
    func delete(_ row:LibraryPanel.Row) {
        confirm("删除所选项目？"){[weak self] in
            guard let self else{return};self.cancelInput();self.commitEditor()
            do {
                switch row {
                case .page(let n,let s,let p,_):try self.session.store.deletePage(notebook:n.id,page:p.id);s.pages.removeAll{$0===p}
                case .section(let n,let s,_):
                    for p in s.all.flatMap(\.pages) {try self.session.store.deletePage(notebook:n.id,page:p.id)}
                    func remove(_ nodes:[MojianCore.Section])->[MojianCore.Section]{nodes.filter{$0 !== s}.map{node in node.children=remove(node.children);return node}}
                    n.sections=remove(n.sections)
                case .notebook(let n):for p in n.pages {try self.session.store.deletePage(notebook:n.id,page:p.id)};self.session.notebooks.removeAll{$0===n}
                }
                if self.session.notebooks.isEmpty {self.session.notebooks=[Notebook(sections:[MojianCore.Section()])]}
                let n=self.session.notebooks.contains(where:{$0===self.session.notebook}) ? self.session.notebook : self.session.notebooks[0],s=n.allSections.first
                // The removed page must not be written again by navigation/autosave.
                self.session.document=nil;try self.session.select(notebook:n,section:s,page:s?.pages.first);self.canvas.refresh(reset:true)
            } catch {self.showError(error)}
        }
    }
    func newContainer() {
        let a=UIAlertController(title:"新建",message:nil,preferredStyle:.actionSheet)
        a.addAction(UIAlertAction(title:"笔记本",style:.default){[weak self] _ in self?.prompt("新笔记本"){text in guard let self else{return};let n=Notebook(title:text);self.session.notebooks.append(n);try? self.session.select(notebook:n,section:nil,page:nil);self.refresh()}})
        a.addAction(UIAlertAction(title:"分区",style:.default){[weak self] _ in self?.prompt("新分区"){text in guard let self else{return};let s=MojianCore.Section(title:text);if let parent=self.session.section {parent.children.append(s)}else{self.session.notebook.sections.append(s)};try? self.session.select(notebook:self.session.notebook,section:s,page:nil);self.refresh()}})
        a.addAction(UIAlertAction(title:"取消",style:.cancel));anchor(a,to:sections.add);topPresenter.present(a,animated:true)
    }
    func newPageMenu() {
        let a=UIAlertController(title:"新建页面",message:nil,preferredStyle:.actionSheet)
        for (name,paper) in [("无界页面","BOUNDLESS"),("A4 页面","A4")] {a.addAction(UIAlertAction(title:name,style:.default){[weak self] _ in self?.prompt("页面标题",value:"无标题页"){title in guard let self else{return};var v=self.session.page?.view ?? Page.defaultView;v["paperSize"] = .string(paper);v["a4PageCount"] = .number(1);self.session.newPage(title:title,view:v);self.canvas.refresh(reset:true);self.dismiss(animated:true)}})}
        a.addAction(UIAlertAction(title:"导入 PDF 页面",style:.default){[weak self] _ in self?.pick("pdf-pages",types:[.pdf])});a.addAction(UIAlertAction(title:"取消",style:.cancel));anchor(a,to:pages.add);topPresenter.present(a,animated:true)
    }
    func changeView(_ key:String,_ value:JSON) { guard let page=session.page,!(page.paper=="PDF" && key=="paperSize") else{return};page.view[key]=value;session.save();refresh() }
    func toggleNavigation(pages isPages:Bool) {
        if compact || fullPage {
            let controller=UIViewController();controller.view.backgroundColor=Theme.pane
            let panel=LibraryPanel(session:session,pages:isPages);panel.select=(isPages ? pages : sections).select;panel.create=(isPages ? pages : sections).create;panel.rename=(isPages ? pages : sections).rename;panel.delete=(isPages ? pages : sections).delete
            panel.autoresizingMask=[.flexibleWidth,.flexibleHeight];panel.frame=controller.view.bounds;controller.view.addSubview(panel);panel.reload()
            controller.sheetPresentationController?.detents=[.medium(),.large()];controller.sheetPresentationController?.prefersGrabberVisible=true;present(controller,animated:true)
        } else {if isPages {pagesVisible.toggle()}else{sectionsVisible.toggle()};view.setNeedsLayout()}
    }
    func anchor(_ alert:UIAlertController,to source:UIView?=nil) { alert.popoverPresentationController?.sourceView=source ?? view;alert.popoverPresentationController?.sourceRect=source?.bounds ?? CGRect(x:view.bounds.midX,y:100,width:1,height:1) }
}
