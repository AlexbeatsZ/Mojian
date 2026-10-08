import UIKit
import MojianCore

final class LibraryPanel: UIView, UITableViewDataSource, UITableViewDelegate {
    enum Row { case notebook(Notebook), section(Notebook,Section,Int), page(Notebook,Section,Page,Int) }
    let session:WorkspaceSession, pages:Bool
    let table=UITableView(frame:.zero,style:.plain), add=UIButton(type:.system)
    var rows=[Row](), select:((Notebook,Section?,Page?) -> Void)?, create:(() -> Void)?, rename:((Row) -> Void)?, delete:((Row) -> Void)?
    init(session:WorkspaceSession,pages:Bool) {
        self.session=session; self.pages=pages; super.init(frame:.zero)
        backgroundColor=pages ? Theme.pane : Theme.rail
        add.setImage(UIImage(systemName:"plus"),for:.normal); add.tintColor = .white; add.accessibilityLabel=pages ? "新建页面或导入 PDF" : "新建笔记本或分区"
        add.addAction(UIAction{ [weak self] _ in self?.create?() },for:.touchUpInside); addSubview(add)
        table.backgroundColor=backgroundColor; table.separatorStyle = .none; table.dataSource=self; table.delegate=self; table.rowHeight=44; table.register(UITableViewCell.self,forCellReuseIdentifier:"row"); addSubview(table)
    }
    required init?(coder:NSCoder) { fatalError() }
    override func layoutSubviews() { super.layoutSubviews(); add.frame=CGRect(x:bounds.width-44,y:0,width:44,height:44); table.frame=CGRect(x:0,y:44,width:bounds.width,height:bounds.height-44) }
    func reload() {
        rows=[]
        if pages {
            func visit(_ section:Section,depth:Int) {
                if depth>0 { rows.append(.section(session.notebook,section,depth-1)) }
                rows += section.pages.map{.page(session.notebook,section,$0,depth)}
                if session.settings.recursivePages { for child in section.children { visit(child,depth:depth+1) } }
            }
            if let section=session.section { visit(section,depth:0) }
            else if session.settings.recursivePages { for s in session.notebook.sections { visit(s,depth:1) } }
            add.isEnabled=session.section != nil
        } else {
            func visit(_ notebook:Notebook,_ sections:[Section],depth:Int) {
                for s in sections { rows.append(.section(notebook,s,depth)); if s.expanded { visit(notebook,s.children,depth:depth+1) } }
            }
            for n in session.notebooks { rows.append(.notebook(n)); if n===session.notebook { visit(n,n.sections,depth:1) } }
        }
        table.reloadData()
    }
    func tableView(_ tableView:UITableView,numberOfRowsInSection section:Int)->Int { rows.count }
    func tableView(_ tableView:UITableView,cellForRowAt index:IndexPath)->UITableViewCell {
        let cell=tableView.dequeueReusableCell(withIdentifier:"row",for:index),row=rows[index.row]
        var config=cell.defaultContentConfiguration(); config.textProperties.font = .systemFont(ofSize:14); config.textProperties.color = .white
        config.directionalLayoutMargins=NSDirectionalEdgeInsets(top:0,leading:12,bottom:0,trailing:8)
        var active=false
        switch row {
        case .notebook(let n): config.text=n.title; config.image=UIImage(systemName:"book.closed"); active=n===session.notebook && session.section==nil
        case .section(_,let s,let depth):
            config.text=s.title; config.directionalLayoutMargins.leading=CGFloat(12+depth*16); active=s===session.section
            config.image=UIImage(systemName:s.children.isEmpty ? "folder" : s.expanded ? "chevron.down" : "chevron.right")
        case .page(_,_,let p,let depth): config.text=p.title+(p.paper=="PDF" ? "  PDF" : ""); config.directionalLayoutMargins.leading=CGFloat(12+depth*16); active=p===session.page
        }
        config.imageProperties.tintColor=Theme.accent; config.imageProperties.maximumSize=CGSize(width:16,height:16)
        cell.contentConfiguration=config; cell.backgroundColor=active ? Theme.accent.withAlphaComponent(0.15) : backgroundColor; cell.selectionStyle = .none
        return cell
    }
    func tableView(_ tableView:UITableView,didSelectRowAt index:IndexPath) {
        switch rows[index.row] {
        case .notebook(let n): select?(n,nil,session.settings.recursivePages ? n.pages.first : nil)
        case .section(let n,let s,_): select?(n,s,s.pages.first ?? (session.settings.recursivePages ? s.all.flatMap(\.pages).first : nil))
        case .page(let n,let s,let p,_):select?(n,s,p)
        }
    }
    func tableView(_ tableView:UITableView,contextMenuConfigurationForRowAt index:IndexPath,point:CGPoint)->UIContextMenuConfiguration? {
        let row=rows[index.row]
        return UIContextMenuConfiguration(identifier:nil,previewProvider:nil) { [weak self] _ in
            var actions=[UIAction(title:"重命名",image:UIImage(systemName:"pencil")){_ in self?.rename?(row)}]
            if case .section(_,let s,_)=row, !s.children.isEmpty { actions.append(UIAction(title:s.expanded ? "收起" : "展开"){_ in s.expanded.toggle(); self?.session.save(); self?.reload()}) }
            actions.append(UIAction(title:"删除",image:UIImage(systemName:"trash"),attributes:.destructive){_ in self?.delete?(row)})
            return UIMenu(children:actions)
        }
    }
}
