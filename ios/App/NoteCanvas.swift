import UIKit
import MojianCore

private final class DrySceneView: UIView {
    weak var canvas: NoteCanvas?
    override func draw(_ rect: CGRect) {
        guard let canvas,let page=canvas.session.page,let document=canvas.session.document,let c=UIGraphicsGetCurrentContext() else { return }
        c.translateBy(x:canvas.viewport.x,y:canvas.viewport.y); c.scaleBy(x:canvas.viewport.scale,y:canvas.viewport.scale)
        let visible=CGRect(origin:canvas.viewport.document(.zero),size:CGSize(width:bounds.width/canvas.viewport.scale,height:bounds.height/canvas.viewport.scale))
        canvas.renderer.drawBackground(page,context:c,visible:visible)
        canvas.renderer.draw(document,page:page,context:c,visible:visible)
    }
}
final class NoteCanvas: UIView, UIPencilInteractionDelegate, UIGestureRecognizerDelegate {
    let session: WorkspaceSession, renderer: SceneRenderer
    var viewport=Viewport()
    var selected=Set<String>()
    var action: ((PencilAction) -> Void)?
    var toolFeedback: ((PenTool?) -> Void)?
    var beginInput: (() -> Void)?
    var edit: ((String,JSON?) -> Void)?
    var contextMenu: ((CGPoint) -> Void)?
    private let scene=DrySceneView(), wet=CAShapeLayer(), selection=CAShapeLayer(), eraserCircle=CAShapeLayer()
    private var points=[InkSample](), predicted=[InkSample](), lasso=[CGPoint]()
    private var sourceTouch: UITouch?, pencil=false, gesture="", start=CGPoint.zero, last=CGPoint.zero, timestamp=0.0
    private var before: PageDocument?, initialBounds=CGRect.null, resizeHandle:Int?
    private var pressure=PressureEraser(), hasWritten=false, temporaryEraser=false, lastMotion=0.0
    private var timer:Timer?, navigationActive=false, navigationViewport=Viewport(), navigationCentroid=CGPoint.zero, navigationSpan=0.0
    private var pinch:UIPinchGestureRecognizer!, pan:UIPanGestureRecognizer!
    var insertSpace=false
    init(session: WorkspaceSession) {
        self.session=session; renderer=SceneRenderer(store:session.store); super.init(frame:.zero)
        backgroundColor=Theme.workspace; isMultipleTouchEnabled=true; clipsToBounds=true
        scene.backgroundColor=Theme.workspace; scene.canvas=self; scene.isUserInteractionEnabled=false; scene.contentMode = .redraw; addSubview(scene)
        for layer in [wet,selection,eraserCircle] { self.layer.addSublayer(layer); layer.actions=["path":NSNull(),"position":NSNull(),"bounds":NSNull()] }
        selection.fillColor=UIColor.clear.cgColor; selection.strokeColor=UIColor(white:0.85,alpha:1).cgColor; selection.lineWidth=1; selection.lineDashPattern=[2,3]
        eraserCircle.fillColor=UIColor.white.withAlphaComponent(0.08).cgColor; eraserCircle.strokeColor=UIColor.white.cgColor
        let interaction=UIPencilInteraction(); interaction.delegate=self; addInteraction(interaction)
        pan=UIPanGestureRecognizer(target:self,action:#selector(navigate(_:))); pan.minimumNumberOfTouches=2; pan.maximumNumberOfTouches=2; pan.allowedTouchTypes=[NSNumber(value:UITouch.TouchType.direct.rawValue)]; pan.delegate=self; addGestureRecognizer(pan)
        pinch=UIPinchGestureRecognizer(target:self,action:#selector(navigate(_:))); pinch.allowedTouchTypes=pan.allowedTouchTypes; pinch.delegate=self; addGestureRecognizer(pinch)
        let hold=UILongPressGestureRecognizer(target:self,action:#selector(held(_:))); hold.allowedTouchTypes=[NSNumber(value:UITouch.TouchType.direct.rawValue),NSNumber(value:UITouch.TouchType.indirectPointer.rawValue)]; hold.delegate=self; addGestureRecognizer(hold)
        let hover=UIHoverGestureRecognizer(target:self,action:#selector(hovered(_:))); addGestureRecognizer(hover)
        accessibilityIdentifier="note-canvas"; accessibilityLabel="笔记画布"
    }
    required init?(coder:NSCoder) { fatalError() }
    override var canBecomeFirstResponder: Bool { true }
    override func hitTest(_ point:CGPoint,with event:UIEvent?)->UIView? {
        let target=super.hitTest(point,with:event)
        if target != nil,event?.allTouches?.contains(where:{$0.type == .pencil}) == true {return self}
        return target
    }
    private func hitStroke(_ stroke:JSON,at point:CGPoint,radius:Double)->Bool {
        guard renderer.cachedBounds(stroke).insetBy(dx:-radius,dy:-radius).contains(point) else{return false}
        let path=renderer.cachedPath(stroke)
        return path.contains(point,using:InkPath.fillRule(stroke)) || path.copy(strokingWithWidth:radius*2,lineCap:.round,lineJoin:.round,miterLimit:10).contains(point)
    }
    override func layoutSubviews() { super.layoutSubviews(); scene.frame=bounds; redrawSelection() }
    func refresh(reset: Bool = false) {
        if reset {
            cancelInput(); selected=[];renderer.clear()
            let v=session.page.flatMap{session.store.viewport(page:$0.id)} ?? session.document?.json["viewport"] ?? .null
            viewport=Viewport(scale:v["scale"].number(0.75),x:v["translationX"].number(20),y:v["translationY"].number(20))
        }
        if let d=session.document { renderer.trim(to:d) }; scene.setNeedsDisplay(); redrawSelection()
    }
    func setZoom(_ scale:Double) { cancelInput(); viewport.zoom(scale,anchor:CGPoint(x:bounds.midX,y:bounds.midY)); saveViewport(); refresh() }
    func fitWidth() { guard let p=session.page else { return }; setZoom(max(0.5,(bounds.width-24)/p.width)); viewport.x=12; viewport.y=12; saveViewport(); refresh() }
    private func saveViewport() {
        if let page=session.page {session.store.saveViewport(viewport,page:page.id)}
    }
    private func sample(_ t: UITouch) -> InkSample {
        let p=viewport.document(t.preciseLocation(in:self)), force=t.maximumPossibleForce>0 && t.force>0 && t.type == .pencil ? t.force/t.maximumPossibleForce : 0.5
        let azimuth=t.azimuthAngle(in:self),tilt=(Double.pi/2-t.altitudeAngle)*180/Double.pi
        return InkSample(x:p.x,y:p.y,pressure:force,elapsed:max(0,(t.timestamp-timestamp)*1000),tiltX:cos(azimuth)*tilt,tiltY:sin(azimuth)*tilt)
    }
    override func touchesBegan(_ touches:Set<UITouch>,with event:UIEvent?) {
        if (event?.allTouches?.filter{$0.type == .direct && $0.phase != .ended && $0.phase != .cancelled}.count ?? 0)>=2 { cancelInput(); return }
        guard sourceTouch==nil,!navigationActive,session.document != nil,let t=touches.first else { return }
        if t.type == .direct, event?.allTouches?.contains(where:{$0.type == .pencil && $0.phase != .ended}) == true { return }
        beginInput?();sourceTouch=t; pencil=t.type == .pencil; timestamp=t.timestamp; start=sample(t).point; last=start; before=session.document
        points=[]; predicted=[]; lasso=[start]; pressure=PressureEraser(); temporaryEraser=false; initialBounds=selectionBounds(); resizeHandle=nil; becomeFirstResponder()
        let tool=session.settings.tool, writes=pencil || session.settings.fingerWriting && t.type == .direct
        if insertSpace && (pencil || t.type == .direct) { gesture="space" }
        else if !writes && !selected.isEmpty, hitSelection(start) {
            gesture="move"; resizeHandle=Geometry.handle(at:start,bounds:initialBounds,radius:(pencil ? 12 : 24)/viewport.scale)
        } else if writes && tool == .hand { gesture="hand" }
        else if writes && tool == .eraser { gesture="erase"; erase(at:start) }
        else if writes && tool == .lasso { gesture="lasso"; selected=[] }
        else if writes && tool == .shape { gesture="shape"; selected=[]; points=[sample(t)] }
        else if writes && (tool == .pen || tool == .highlighter) { gesture="ink"; selected=[]; points=[sample(t)]; updateWet() }
        else { gesture="direct" }
        lastMotion=t.timestamp
        timer=Timer.scheduledTimer(withTimeInterval:0.025,repeats:true) { [weak self] _ in self?.dwell() }
        redrawSelection()
    }
    override func touchesMoved(_ touches:Set<UITouch>,with event:UIEvent?) {
        guard let t=sourceTouch,touches.contains(t),!navigationActive else { return }
        let batch=event?.coalescedTouches(for:t) ?? [t]
        for t in batch {
            let p=sample(t), delta=CGPoint(x:p.x-start.x,y:p.y-start.y)
            if hypot(p.x-last.x,p.y-last.y)*viewport.scale>1 { lastMotion=t.timestamp }
            switch gesture {
            case "ink":
                if pencil && pressure.update(p,settings:session.settings,eligible:hasWritten) {
                    if !temporaryEraser {toolFeedback?(.eraser)};temporaryEraser=true; points=[]; predicted=[]; wet.path=nil; erase(at:p.point)
                } else if !temporaryEraser { points.append(p) }
            case "erase": erase(at:p.point)
            case "shape": points = [points.first ?? p,p]
            case "shape-line":points=ShapeKind.line.points(from:points[0].point,to:p.point)
            case "lasso": lasso.append(p.point)
            case "direct":
                if hypot(delta.x,delta.y)*viewport.scale>6 { lasso=[start,CGPoint(x:p.x,y:start.y),p.point,CGPoint(x:start.x,y:p.y)] }
            case "move":
                if hypot(delta.x,delta.y)*viewport.scale>6, var d=before {
                    let next=resizeHandle.map{ Geometry.resize(initialBounds,handle:$0,delta:delta,minimum:min(24/viewport.scale,min(initialBounds.width,initialBounds.height))) } ?? initialBounds.offsetBy(dx:delta.x,dy:delta.y)
                    d.transform(ids:selected,from:initialBounds,to:next); session.document=d; scene.setNeedsDisplay()
                }
            case "hand": viewport.x+=(p.x-last.x)*viewport.scale*session.settings.handSensitivity; viewport.y+=(p.y-last.y)*viewport.scale*session.settings.handSensitivity; scene.setNeedsDisplay()
            case "space":
                if var d=before {
                    let ids=Set(["strokes","objects","textItems","tags"].flatMap{ key in d.json[key].array.filter{ !($0["isPageBackground"].bool()) && Geometry.bounds($0,category:key).minY>=start.y }.map{$0["id"].string} })
                    let b=before!.contentBounds; if !b.isNull { d.transform(ids:ids,from:b,to:b.offsetBy(dx:0,dy:delta.y)); session.document=d; scene.setNeedsDisplay() }
                }
            default:break
            }; last=p.point
        }
        predicted=session.settings.prediction && gesture=="ink" && !temporaryEraser ? (event?.predictedTouches(for:t) ?? []).map(sample) : []
        updateWet(); redrawSelection()
    }
    override func touchesEnded(_ touches:Set<UITouch>,with event:UIEvent?) {
        guard let t=sourceTouch,touches.contains(t) else { return }
        let end=sample(t).point
        switch gesture {
        case "ink": if !temporaryEraser && !points.isEmpty { points.append(sample(t)); session.document?.strokes.append(session.settings.stroke(points)); hasWritten=true }
        case "shape-line": if !points.isEmpty { session.document?.strokes.append(session.settings.stroke(points,shape:true));hasWritten=true }
        case "shape": if let first=points.first { session.document?.strokes.append(session.settings.stroke(session.settings.shape.points(from:first.point,to:end),shape:true)) }
        case "lasso": if let d=session.document { selected=Geometry.selected(in:d,polygon:lasso) }
        case "direct":
            if lasso.count>=4,let d=session.document { selected=Geometry.selected(in:d,polygon:lasso) }
            else { directTap(end) }
        default:break
        }
        if let before { session.commit(before) }
        if gesture=="hand" { saveViewport() }
        finish(); growPage(); refresh()
    }
    override func touchesCancelled(_ touches:Set<UITouch>,with event:UIEvent?) { cancelInput() }
    private func finish() { timer?.invalidate(); timer=nil; sourceTouch=nil; before=nil; gesture=""; points=[]; predicted=[]; lasso=[];if temporaryEraser{toolFeedback?(nil)};temporaryEraser=false; wet.path=nil; eraserCircle.path=nil; pressure=PressureEraser() }
    func cancelInput() { if let before { session.document=before }; finish(); scene.setNeedsDisplay(); redrawSelection() }
    private func dwell() {
        guard let t=sourceTouch,gesture=="ink",let last=points.last,!temporaryEraser else { return }
        let p=InkSample(x:last.x,y:last.y,pressure:last.pressure,elapsed:(ProcessInfo.processInfo.systemUptime-timestamp)*1000)
        if pencil && pressure.update(p,settings:session.settings,eligible:hasWritten) { toolFeedback?(.eraser);temporaryEraser=true; points=[]; wet.path=nil; erase(at:p.point); return }
        if session.settings.straighten,points.count>2,(!pencil || pressure.locked || last.pressure<session.settings.pressureThreshold),ProcessInfo.processInfo.systemUptime-lastMotion>=session.settings.straightenMS/1000 {
            let a=points[0]; points=ShapeKind.line.points(from:a.point,to:last.point); gesture="shape-line"; updateWet()
        }
        _=t
    }
    private func erase(at p: CGPoint) {
        guard var d=session.document else { return }; let radius=session.settings.eraserRadiusMM*unitsPerMM
        d.strokes.removeAll { stroke in
            guard renderer.cachedBounds(stroke).insetBy(dx:-radius,dy:-radius).contains(p) else{return false}
            let path=renderer.cachedPath(stroke)
            return path.contains(p,using:InkPath.fillRule(stroke)) || path.copy(strokingWithWidth:radius*2,lineCap:.round,lineJoin:.round,miterLimit:10).contains(p)
        }
        d.tags.removeAll { Geometry.bounds($0,category:"tags").insetBy(dx:-radius,dy:-radius).contains(p) }; session.document=d; scene.setNeedsDisplay()
        let s=viewport.screen(p),r=radius*viewport.scale
        eraserCircle.path=UIBezierPath(ovalIn:CGRect(x:s.x-r,y:s.y-r,width:r*2,height:r*2)).cgPath
    }
    private func updateWet() {
        guard !temporaryEraser, !points.isEmpty,let page=session.page else {
            if gesture=="lasso" || gesture=="direct" || gesture=="space" { updateLasso() }; return
        }
        let isShape=gesture=="shape" || gesture=="shape-line"
        let samples=gesture=="shape" && points.count>=2 ? session.settings.shape.points(from:points[0].point,to:points.last!.point) : points+predicted
        let record=session.settings.stroke(samples,shape:isShape)
        var transform=CGAffineTransform(a:viewport.scale,b:0,c:0,d:viewport.scale,tx:viewport.x,ty:viewport.y)
        wet.path=InkPath.make(record,completed:false).copy(using:&transform); wet.fillColor=renderer.inkColor(record,dark:page.dark).cgColor
        wet.strokeColor=nil; wet.lineDashPattern=nil; wet.opacity=Float(record["opacityPercent"].number(100)/100)
    }
    private func updateLasso() {
        let path=UIBezierPath()
        for (i,p) in lasso.enumerated() { if i==0 { path.move(to:viewport.screen(p)) } else { path.addLine(to:viewport.screen(p)) } }
        if gesture=="direct" { path.close() }; wet.path=path.cgPath; wet.fillColor=nil; wet.strokeColor=Theme.accent.cgColor; wet.lineWidth=1.5; wet.lineDashPattern=[4,3]; wet.opacity=1
    }
    func selectionBounds() -> CGRect {
        guard let d=session.document else { return .null }; var b=CGRect.null
        for key in ["strokes","objects","textItems","tags"] { for item in d.json[key].array where selected.contains(item["id"].string) { b=b.union(Geometry.bounds(item,category:key)) } }; return b
    }
    private func hitSelection(_ p: CGPoint) -> Bool {
        let b=selectionBounds(); guard !b.isNull else { return false }
        if Geometry.handle(at:p,bounds:b,radius:24/viewport.scale) != nil { return true }
        guard let d=session.document else { return false }
        return ["strokes","objects","textItems","tags"].contains { key in d.json[key].array.contains { selected.contains($0["id"].string) && Geometry.bounds($0,category:key).contains(p) } }
    }
    func redrawSelection() {
        let b=selectionBounds(); guard !b.isNull else { selection.path=nil; return }
        let r=CGRect(origin:viewport.screen(b.origin),size:CGSize(width:b.width*viewport.scale,height:b.height*viewport.scale)),path=UIBezierPath(rect:r)
        for p in Geometry.handles(r) { path.append(UIBezierPath(rect:CGRect(x:p.x-3,y:p.y-3,width:6,height:6))) }; selection.path=path.cgPath
    }
    private func directTap(_ p:CGPoint) {
        guard let d=session.document else { return }
        let hit=renderer.ordered(d).reversed().first { key,item in !item["isPageBackground"].bool() && (key=="strokes" ? hitStroke(item,at:p,radius:8/viewport.scale) : Geometry.bounds(item,category:key).contains(p)) }
        if let (key,item)=hit {
            if selected.contains(item["id"].string),key != "strokes" { edit?(key,item) }
            selected=[item["id"].string]
        } else { selected=[]; edit?("newText",.object(["x":.number(p.x),"y":.number(p.y)])) }
    }
    private func growPage() {
        guard let page=session.page,let d=session.document else { return }
        if page.grow(content:d.contentBounds,visibleBottom:viewport.document(CGPoint(x:0,y:bounds.maxY)).y) { session.save() }
    }
    func gestureRecognizer(_ g:UIGestureRecognizer,shouldRecognizeSimultaneouslyWith other:UIGestureRecognizer)->Bool { g===pan && other===pinch || g===pinch && other===pan }
    func gestureRecognizer(_ g:UIGestureRecognizer,shouldReceive touch:UITouch)->Bool { if g is UILongPressGestureRecognizer { return !session.settings.fingerWriting && sourceTouch?.type != .pencil }; return true }
    @objc private func navigate(_ g:UIGestureRecognizer) {
        if g.state == .began {
            if !navigationActive { cancelInput();beginInput?();navigationActive=true; navigationViewport=viewport; navigationCentroid=g.location(in:self); navigationSpan=pinch.scale }
        }
        if g.state == .changed, navigationActive {
            let centroid=g.location(in:self), anchor=navigationViewport.document(navigationCentroid)
            let scale=session.settings.pinchLocked ? navigationViewport.scale : min(64,max(0.5,navigationViewport.scale*pinch.scale/max(0.01,navigationSpan)))
            viewport=Viewport(scale:scale,x:centroid.x-anchor.x*scale,y:centroid.y-anchor.y*scale); refresh()
        }
        if [.ended,.cancelled,.failed].contains(g.state), pan.state != .changed && pinch.state != .changed { navigationActive=false; saveViewport(); growPage() }
    }
    @objc private func held(_ g:UILongPressGestureRecognizer) { if g.state == .began { let p=viewport.document(g.location(in:self)); cancelInput(); directTap(p); contextMenu?(g.location(in:self)) } }
    @objc private func hovered(_ g:UIHoverGestureRecognizer) {
        if [.ended,.cancelled].contains(g.state) { eraserCircle.path=nil; return }
        let p=g.location(in:self); let r=session.settings.tool == .eraser ? session.settings.eraserRadiusMM*unitsPerMM*viewport.scale : 2
        eraserCircle.path=UIBezierPath(ovalIn:CGRect(x:p.x-r,y:p.y-r,width:r*2,height:r*2)).cgPath
    }
    func pencilInteractionDidTap(_ interaction:UIPencilInteraction) { action?(resolve(session.settings.doubleTap,preferred:UIPencilInteraction.preferredTapAction)) }
    @available(iOS 17.5, *) func pencilInteraction(_ interaction:UIPencilInteraction,didReceiveTap tap:UIPencilInteraction.Tap) { action?(resolve(session.settings.doubleTap,preferred:UIPencilInteraction.preferredTapAction)) }
    @available(iOS 17.5, *) func pencilInteraction(_ interaction:UIPencilInteraction,didReceiveSqueeze squeeze:UIPencilInteraction.Squeeze) {
        guard squeeze.phase == .ended else { return }; action?(resolve(session.settings.squeeze,preferred:UIPencilInteraction.preferredSqueezeAction))
    }
    private func resolve(_ action:PencilAction,preferred:UIPencilPreferredAction)->PencilAction {
        guard action == .system else { return action }
        switch preferred { case .switchEraser:return .eraser; case .switchPrevious:return .previousTool
        case .showColorPalette,.showInkAttributes,.showContextualPalette:return .palette; default:return .none }
    }
    override var keyCommands:[UIKeyCommand]? {
        var keys=[UIKeyCommand(input:"z",modifierFlags:.command,action:#selector(undoKey)),UIKeyCommand(input:"z",modifierFlags:[.command,.shift],action:#selector(redoKey)),UIKeyCommand(input:UIKeyCommand.inputDelete,modifierFlags:[],action:#selector(deleteKey))]
        for key in [session.settings.penKeyOne,session.settings.penKeyTwo] where !key.isEmpty {
            keys.append(UIKeyCommand(input:key,modifierFlags:[],action:#selector(penKey(_:))))
        }; return keys
    }
    @objc private func undoKey() { action?(.undo) }
    @objc private func redoKey() { action?(.redo) }
    @objc private func deleteKey() { deleteSelection() }
    @objc private func penKey(_ key:UIKeyCommand) { action?(key.input == session.settings.penKeyOne ? session.settings.penKeyOneAction : session.settings.penKeyTwoAction) }
    func deleteSelection() { let ids=selected; session.mutate{$0.delete(ids:ids)}; selected=[]; refresh() }
}
