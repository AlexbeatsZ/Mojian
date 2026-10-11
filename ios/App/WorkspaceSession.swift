import UIKit
import MojianCore

@MainActor final class WorkspaceSession {
    let store: WorkspaceStore
    var notebooks: [Notebook]
    var settings: Settings
    var notebook: Notebook
    var section: Section?
    var page: Page?
    var document: PageDocument?
    private var documents=[String:PageDocument]()
    private var histories=[String:([PageDocument],[PageDocument])]()
    var changed: (() -> Void)?
    var error: ((Error) -> Void)?
    var previousTool: PenTool = .pen
    init(root: URL) throws {
        store=try WorkspaceStore(root:root); notebooks=try store.loadLibrary(); settings=try store.loadSettings()
        notebook=notebooks[0]; section=notebook.allSections.first; page=section?.pages.first
        if let id=store.lastPageID(),let n=notebooks.first(where:{$0.pages.contains(where:{$0.id==id})}),let s=n.allSections.first(where:{$0.pages.contains(where:{$0.id==id})}) {
            notebook=n;section=s;page=s.pages.first{$0.id==id}
        }
        if let p=page { document=try store.loadPage(notebook:notebook.id,page:p.id); documents[p.id]=document }
        store.onError = { [weak self] error in DispatchQueue.main.async { self?.error?(error) } }
        store.saveLibrary(notebooks,activePage:page?.id)
    }
    func save() {
        if let d=document { documents[d.pageID]=d; store.savePage(d) }
        store.saveLibrary(notebooks,activePage:page?.id); store.saveSettings(settings)
    }
    func flush() { save(); store.flush() }
    func select(notebook n: Notebook, section s: Section?, page p: Page?) throws {
        save(); store.flush()
        // Load before changing the binding; a corrupt destination cannot clear the current page.
        let next = try p.map { try documents[$0.id] ?? store.loadPage(notebook:n.id,page:$0.id) }
        notebook=n; section=s; page=p; document=next
        store.saveLibrary(notebooks,activePage:p?.id); changed?()
    }
    func mutate(_ action: (inout PageDocument) -> Void) {
        guard var d=document else { return }
        let before=d; action(&d); guard d != before else { return }
        var h=histories[d.pageID] ?? ([],[]); h.0.append(before); if h.0.count>100 { h.0.removeFirst() }; h.1=[]; histories[d.pageID]=h
        document=d; save(); changed?()
    }
    func commit(_ before: PageDocument) {
        guard let d=document, d != before else { return }
        var h=histories[d.pageID] ?? ([],[]); h.0.append(before); h.1=[]; histories[d.pageID]=h; save(); changed?()
    }
    func undo() {
        guard let d=document, var h=histories[d.pageID], let before=h.0.popLast() else { return }
        h.1.append(d); histories[d.pageID]=h; document=before; save(); changed?()
    }
    func redo() {
        guard let d=document, var h=histories[d.pageID], let next=h.1.popLast() else { return }
        h.0.append(d); histories[d.pageID]=h; document=next; save(); changed?()
    }
    func choose(_ tool: PenTool) { if settings.tool != tool { previousTool=settings.tool }; settings.tool=tool; store.saveSettings(settings); changed?() }
    func document(for p: Page, notebook n: Notebook) throws -> PageDocument {
        if page?.id==p.id, let document { return document }; if let cached=documents[p.id] { return cached }
        return try store.loadPage(notebook:n.id,page:p.id)
    }
    func newPage(title: String, view: JSON) {
        guard let section else { return }
        let p=Page(title:title.isEmpty ? "无标题页" : title); p.view=view; section.pages.append(p)
        do { try select(notebook:notebook,section:section,page:p) } catch { self.error?(error) }
    }
    func appendImported(_ n: Notebook) throws {
        notebooks.append(n); let s=n.allSections.first; try select(notebook:n,section:s,page:s?.pages.first); save()
    }
}
