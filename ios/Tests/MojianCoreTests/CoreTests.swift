import XCTest
@testable import MojianCore

final class CoreTests: XCTestCase {
    func scratch() throws -> URL {
        let url=URL(fileURLWithPath:"/tmp/.agents/mojian-ios-tests").appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at:url,withIntermediateDirectories:true); addTeardownBlock { try? FileManager.default.removeItem(at:url) }; return url
    }
    func fixture() throws -> PageDocument {
        try PageDocument(JSON.read(Data(contentsOf:Bundle.module.url(forResource:"windows-page",withExtension:"json",subdirectory:"Fixtures")!)))
    }
    func testLosslessWindowsPersistenceAndRecovery() throws {
        let root=try scratch(), store=try WorkspaceStore(root:root), d=try fixture()
        store.savePage(d); store.flush(); XCTAssertEqual(try store.loadPage(notebook:d.notebookID,page:d.pageID).json["futureExtension"],d.json["futureExtension"])
        var changed=d; changed.texts=[]; store.savePage(changed); store.flush()
        try Data("{broken".utf8).write(to:store.pageURL(notebook:d.notebookID,page:d.pageID))
        let restored=try store.loadPage(notebook:d.notebookID,page:d.pageID)
        XCTAssertEqual(restored.texts,d.texts); XCTAssertEqual(restored.strokes,d.strokes)
    }
    func testCorruptDataNeverBecomesEmpty() throws {
        let store=try WorkspaceStore(root:scratch()), url=try store.pageURL(notebook:"n",page:"p")
        try FileManager.default.createDirectory(at:url.deletingLastPathComponent(),withIntermediateDirectories:true)
        try Data("bad".utf8).write(to:url); XCTAssertThrowsError(try store.loadPage(notebook:"n",page:"p"))
        for id in ["..","../escape","/root","a/b",""] { XCTAssertThrowsError(try WorkspaceStore.safe(id)) }
    }
    func testMjnLosslessRoundTripWithAssetsAndRemappedLinks() throws {
        let root=try scratch(), store=try WorkspaceStore(root:root), d=try fixture()
        let p=Page(.object(["id":.string(d.pageID),"title":.string("化学"),"viewSettings":Page.defaultView]))
        let n=Notebook(id:d.notebookID,title:"测试笔记本",sections:[Section(id:"s",pages:[p])])
        store.savePage(d); try store.writeAsset(Data([1,2,3]),notebook:n.id,page:p.id,name:"sample.bin")
        let archive=root.appendingPathComponent("roundtrip.mjn"); try NotebookArchive.export(n,store:store,to:archive)
        let copy=try NotebookArchive.importNotebook(from:archive,store:store)
        XCTAssertNotEqual(copy.id,n.id); XCTAssertNotEqual(copy.pages[0].id,p.id)
        let loaded=try store.loadPage(notebook:copy.id,page:copy.pages[0].id)
        XCTAssertEqual(loaded.strokes,d.strokes); XCTAssertEqual(loaded.texts,d.texts); XCTAssertEqual(loaded.json["futureExtension"],d.json["futureExtension"])
        XCTAssertEqual(try Data(contentsOf:store.assets(notebook:copy.id,page:copy.pages[0].id).appendingPathComponent("sample.bin")),Data([1,2,3]))
    }
    func testZoomAnchorClampsAndStillPans() {
        var v=Viewport(scale:1,x:37,y:-11); let p=CGPoint(x:210,y:140), before=v.document(p)
        v.zoom(128,anchor:p); XCTAssertEqual(v.scale,64); XCTAssertEqual(v.document(p).x,before.x,accuracy:1e-8)
        v.x+=20; v.zoom(0.1,anchor:p); XCTAssertEqual(v.scale,0.5)
    }
    func testTinySelectionInteriorAndAspectPreservingResize() {
        let r=CGRect(x:10,y:10,width:8,height:100)
        XCTAssertNil(Geometry.handle(at:CGPoint(x:14,y:60),bounds:r,radius:24))
        let next=Geometry.resize(r,handle:4,delta:CGPoint(x:20,y:30),minimum:8)
        XCTAssertEqual(next.width/next.height,r.width/r.height,accuracy:1e-8); XCTAssertEqual(next.minX,r.minX)
        XCTAssertEqual(Geometry.resize(r,handle:3,delta:CGPoint(x:20,y:30),minimum:8).height,r.height)
        XCTAssertEqual(Geometry.resize(r,handle:4,delta:CGPoint(x:-500,y:-500),minimum:8).width,8,accuracy:0.001)
    }
    func testContainmentDoesNotSelectPartialLargeImage() throws {
        let d=try fixture(), polygon=[CGPoint(x:0,y:0),CGPoint(x:45,y:0),CGPoint(x:45,y:80),CGPoint(x:0,y:80)]
        let ids=Geometry.selected(in:d,polygon:polygon)
        XCTAssertTrue(ids.contains("ink-fixture")); XCTAssertFalse(ids.contains("image-fixture"))
    }
    func testPressureTravelLatchesAcrossRetracedLowPressurePoints() {
        var e=PressureEraser(), s=Settings(); s.travelMM=1; s.dwellMS=50
        _=e.update(InkSample(x:0,y:0,pressure:0.2),settings:s,eligible:true)
        _=e.update(InkSample(x:3,y:0,pressure:0.2,elapsed:10),settings:s,eligible:true)
        _=e.update(InkSample(x:0,y:0,pressure:1,elapsed:20),settings:s,eligible:true)
        XCTAssertTrue(e.locked); XCTAssertFalse(e.update(InkSample(x:0,y:0,pressure:1,elapsed:200),settings:s,eligible:true))
    }
    func testPressureActivatedEraserCanTravel() {
        var e=PressureEraser(), s=Settings(); s.dwellMS=50
        _=e.update(InkSample(x:0,y:0,pressure:1),settings:s,eligible:true)
        XCTAssertTrue(e.update(InkSample(x:0,y:0,pressure:1,elapsed:60),settings:s,eligible:true))
        XCTAssertTrue(e.update(InkSample(x:100,y:100,pressure:1,elapsed:80),settings:s,eligible:true))
    }
    func testSevenShapesAndMonotonicPaperGrowth() {
        for shape in ShapeKind.allCases { XCTAssertGreaterThanOrEqual(shape.points(from:.zero,to:CGPoint(x:100,y:100)).count,2) }
        let p=Page(); XCTAssertTrue(p.grow(content:CGRect(x:1000,y:1000,width:20,height:20),visibleBottom:100000))
        let size=p.width; XCTAssertFalse(p.grow(content:.null,visibleBottom:200000)); XCTAssertEqual(p.width,size)
        var v=p.view; v["paperSize"] = .string("A4"); p.view=v
        XCTAssertTrue(p.grow(content:.null,visibleBottom:p.height-1)); XCTAssertEqual(p.sheetCount,2)
    }
    func testSelectionTransformsSourceOutlinesWithoutFlattening() throws {
        var d=try fixture(); let before=d.strokes[1], b=Geometry.bounds(before,category:"strokes")
        d.transform(ids:["vector-fixture"],from:b,to:b.offsetBy(dx:40,dy:20))
        XCTAssertEqual(d.strokes[1]["vectorPath"],before["vectorPath"])
        XCTAssertEqual(d.strokes[1]["sourceBrush"],before["sourceBrush"])
        XCTAssertEqual(Geometry.bounds(d.strokes[1],category:"strokes").minX,b.minX+40)
    }
    func testPencilBindingsPersistIndependently() throws {
        let store=try WorkspaceStore(root:scratch()); var s=Settings(); s.doubleTap = .lasso; s.squeeze = .undo; s.penKeyOne="F13"
        store.saveSettings(s); store.flush(); XCTAssertEqual(try store.loadSettings(),s)
    }
    func testSourceVectorRepeatedCoordinatesAndBezier() throws {
        let path=try InkPath.parse("M 10,20 L 30,20 30,40 Z M 100,100 C 110,100 110,120 120,120 Q 130,140 140,130 Z")
        XCTAssertTrue(path.contains(CGPoint(x:25,y:25)))
        XCTAssertEqual(path.boundingBoxOfPath.minX,10)
        XCTAssertThrowsError(try InkPath.parse("M 0 0 L NaN 4"))
    }
    func testPaginatedVectorExportDropsHistoricalBlankSheets() throws {
        let page=Page();var v=page.view;v["paperSize"] = .string("A4");v["a4PageCount"] = .number(10);page.view=v
        let d=try fixture();let sheets=ExportPlan.sheets(page:page,document:d)
        XCTAssertEqual(sheets.count,1);XCTAssertEqual(sheets[0].widthPoints,595.27559055,accuracy:0.001)
        var second=d;let offset=page.height+12*unitsPerMM;second.strokes.append(Settings().stroke([InkSample(x:10,y:offset+30),InkSample(x:60,y:offset+60)]))
        XCTAssertEqual(ExportPlan.sheets(page:page,document:second).count,2)
    }
    func testNegativeOverflowAndTallBoundlessExport() {
        let p=Page();var d=PageDocument(notebook:"n",page:"p")
        d.strokes=[Settings().stroke([InkSample(x:-40,y:-20),InkSample(x:100,y:4000)])]
        let sheets=ExportPlan.sheets(page:p,document:d);XCTAssertGreaterThan(sheets.count,1);XCTAssertLessThan(sheets[0].source.minX,0)
    }
    func testViewportPersistenceDoesNotRewritePage() throws {
        let store=try WorkspaceStore(root:scratch()),d=try fixture();try store.writePage(d)
        let before=try Data(contentsOf:store.pageURL(notebook:d.notebookID,page:d.pageID))
        store.saveViewport(Viewport(scale:64,x:40,y:90),page:d.pageID);store.flush()
        XCTAssertEqual(try Data(contentsOf:store.pageURL(notebook:d.notebookID,page:d.pageID)),before)
        XCTAssertEqual(store.viewport(page:d.pageID)?["scale"].number(),64)
    }
    func testBackgroundExchangeNamesAndLegacyAliases() {
        XCTAssertEqual(Background.ruled.rawValue,"RULE_STANDARD")
        XCTAssertEqual(Background.restore("RULED_NARROW"),.ruledNarrow)
        XCTAssertEqual(Background.restore("RULE_TWO_COLUMN_WIDE"),.twoColumnWide)
        XCTAssertFalse(Background.ruledClean.showsMargin)
        XCTAssertEqual(Background.gridDense.spacing,14)
        XCTAssertEqual(Background.twoColumnWide.spacing,37.8,accuracy:0.0001)
    }

    func testSourceEvenOddHolesRemainHoles() throws {
        let stroke=JSON.object(["vectorPath":.string("F0 M0 0 L20 0 20 20 0 20 Z M5 5 L15 5 15 15 5 15 Z")])
        let path=InkPath.make(stroke)
        XCTAssertEqual(InkPath.fillRule(stroke),.evenOdd)
        XCTAssertTrue(path.contains(CGPoint(x:2,y:2),using:InkPath.fillRule(stroke)))
        XCTAssertFalse(path.contains(CGPoint(x:10,y:10),using:InkPath.fillRule(stroke)))
    }

    func testAuthoredInkUsesWindowsIntegerMilliseconds() throws {
        struct WindowsPoint:Decodable {let elapsedMilliseconds:Int64}
        let sample=InkSample(x:1,y:2,elapsed:16.435)
        XCTAssertEqual(try JSONDecoder().decode(WindowsPoint.self,from:sample.json.data()).elapsedMilliseconds,16)
    }

    func testStructurallyInvalidJsonDoesNotBecomeBlankPage() throws {
        let store=try WorkspaceStore(root:scratch()),d=try fixture(),url=try store.pageURL(notebook:d.notebookID,page:d.pageID)
        try store.writePage(d);try store.writePage(d)
        try Data("[]".utf8).write(to:url)
        XCTAssertEqual(try store.loadPage(notebook:d.notebookID,page:d.pageID).strokes,d.strokes)
        try store.writePage(d)
        XCTAssertEqual(try PageDocument(JSON.read(Data(contentsOf:url.appendingPathExtension("previous")))).strokes,d.strokes)
        var invalid=d.json;invalid["strokes"] = .string("invalid")
        XCTAssertThrowsError(try PageDocument(invalid))
        XCTAssertThrowsError(try PageDocument(.array([])))
    }

}
