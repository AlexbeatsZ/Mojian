using System.Reflection;
using System.Threading;
using System.Windows;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;
using Xunit;

namespace Notein_win.Tests;

public sealed class ShapeGeometryTests
{
    [Fact] public void StationaryTimerIgnoresJitterAndDoesNotRequireMoveEvents()
    {
        var hold = new HoldToStraighten(); hold.Begin(new(0,0),0); hold.Move(new(100,30),100,2.5);
        for (var i=1;i<=50;i++) hold.Move(new(100+i%2,30),100+i*10,2.5);
        Assert.False(hold.TrySnap(699,600)); Assert.True(hold.TrySnap(700,600)); Assert.False(hold.TrySnap(800,600));
        hold.Cancel(); Assert.False(hold.TrySnap(2000,600));
    }
    [Fact] public void DwellRestartsOnMovementAndRejectsRetracedOrClosedPaths()
    {
        var hold = new HoldToStraighten(); hold.Begin(new(0,0),0); hold.Move(new(100,0),10,2);
        hold.Move(new(130,10),550,2); Assert.False(hold.TrySnap(1000,600)); Assert.True(hold.TrySnap(1150,600));
        hold.Begin(new(0,0),0); hold.Move(new(100,0),100,2); hold.Move(new(10,0),200,2); Assert.False(hold.TrySnap(2000,600));
    }
    [Fact] public void CircleAndRectangleKeepExactGeometryForReverseDrags()
    {
        foreach (var end in new[] { new Point(120,80), new Point(-120,-80) })
        {
            var circle = ShapeGeometry.Points(ShapeKind.Circle,new(0,0),end);
            var center = new Point((circle.Min(p=>p.X)+circle.Max(p=>p.X))/2,(circle.Min(p=>p.Y)+circle.Max(p=>p.Y))/2);
            Assert.All(circle,p=>Assert.InRange((p.ToPoint()-center).Length,59.999,60.001));
            var rect=ShapeGeometry.Points(ShapeKind.Rectangle,new(0,0),end);
            Assert.Equal(5,rect.Count); Assert.Equal(rect[0].ToPoint(),rect[^1].ToPoint());
            Assert.All(rect.Zip(rect.Skip(1)),pair=>Assert.True(pair.First.X==pair.Second.X || pair.First.Y==pair.Second.Y));
        }
    }
    [Fact] public void ShapeToolAndDelayRoundTripWithoutChangingOriginalPenSelection()
    {
        var shelf=ToolShelf.FromJson("{\"selectedId\":\"pen-black\",\"tools\":[{\"id\":\"pen-black\",\"kind\":\"Pen\"}]}")!;
        Assert.Equal("pen-black",shelf.SelectedId);
        var shape=shelf.Tools.Single(t=>t.Id=="shape-default"); shape.ShapeKind=ShapeKind.Rectangle; shape.StraightenDelayMillis=1250; shape.HoldToStraightenEnabled=false;
        var restored=ToolShelf.FromJson(shelf.ToJson())!.Tools.Single(t=>t.Id=="shape-default");
        Assert.Equal(ShapeKind.Rectangle,restored.ShapeKind); Assert.Equal(1250,restored.StraightenDelayMillis); Assert.False(restored.HoldToStraightenEnabled);
    }
    [Fact] public void NotebookPackageSharesEditableShapePointsWithAndroid()
    {
        var root=System.IO.Path.Combine(System.IO.Path.GetTempPath(),".agents","mojian-shape-export",Guid.NewGuid().ToString("N"));
        try {
            var store=new WorkspaceStore(root);
            var notebook=NotebookWorkspace.Sample(); var page=notebook.SelectedPage()!;
            var document=new PageDocument(notebook.Id,page.Id);
            document.Strokes.Add(new StrokeRecord("shape-one",DrawingToolKind.Pen,InkPalette.Blue,.8f,100) {
                BrushProfile="shape-v1", Points=ShapeGeometry.Points(ShapeKind.Rectangle,new(10,20),new(110,80))
            });
            store.SavePage(document);
            var package=System.IO.Path.Combine(root,"shapes.mjn");
            NotebookPackageService.Export(package,notebook,store,page.Id);
            using var zip=System.IO.Compression.ZipFile.OpenRead(package);
            using var reader=new System.IO.StreamReader(zip.GetEntry($"pages/{page.Id}.json")!.Open());
            var json=System.Text.Json.Nodes.JsonNode.Parse(reader.ReadToEnd())!;
            var shape=json["strokes"]![0]!;
            Assert.Equal("shape-v1",shape["brushProfile"]!.GetValue<string>());
            Assert.Equal(0,shape["kind"]!.GetValue<int>()); Assert.Equal(1,shape["color"]!.GetValue<int>());
            var restored=System.Text.Json.JsonSerializer.Deserialize<StrokeRecord>(shape.ToJsonString(),new System.Text.Json.JsonSerializerOptions { PropertyNameCaseInsensitive=true })!;
            Assert.Equal("shape-one",restored.Id); Assert.Equal(.8f,restored.WidthMm);
            Assert.Equal(5,restored.Points.Count); Assert.Equal(110,restored.Points[2].X); Assert.Equal(80,restored.Points[2].Y);
        } finally { if(System.IO.Directory.Exists(root)) System.IO.Directory.Delete(root,true); }
    }
    [Fact] public void RealCanvasShapeCreationUndoPersistenceAndCancellation()
    {
        Exception? failure=null;
        WpfTestExecutor.Run(()=> {
            try {
                var canvas=new NoteCanvas { Document=new PageDocument("n","p"), SelectedTool=new ToolPreset("shape-default",DrawingToolKind.Shape) { ShapeKind=ShapeKind.Rectangle } };
                void Call(string name, params object[] args) => typeof(NoteCanvas).GetMethod(name,BindingFlags.Instance|BindingFlags.NonPublic)!.Invoke(canvas,args);
                Call("BeginDirectPointer",new Point(50,50),false);
                Call("ContinuePointer",new Point(150,100),.5f,0f,0f,false);
                Call("EndPointer",false);
                var shape=Assert.Single(canvas.Document.Strokes);
                Assert.Equal("shape-v1",shape.BrushProfile); Assert.Equal(5,shape.Points.Count);
                var root=System.IO.Path.Combine(System.IO.Path.GetTempPath(),".agents","mojian-shapes-test",Guid.NewGuid().ToString("N"));
                try { var store=new WorkspaceStore(root); store.SavePage(canvas.Document); Assert.Equal(5,Assert.Single(store.LoadPage("n","p").Strokes).Points.Count); }
                finally { if (System.IO.Directory.Exists(root)) System.IO.Directory.Delete(root,true); }
                canvas.Undo(); Assert.Empty(canvas.Document.Strokes); canvas.Redo(); Assert.Single(canvas.Document.Strokes);
                Call("BeginDirectPointer",new Point(200,200),false);
                Call("ContinuePointer",new Point(250,250),.5f,0f,0f,false);
                Call("CancelDirectPointerForPageNavigation"); Assert.Single(canvas.Document.Strokes);
                canvas.SelectedTool=new ToolPreset("pen",DrawingToolKind.Pen);
                Call("BeginPointer",new Point(10,10),.5f,0f,0f,true,false,false);
                Call("ContinuePointer",new Point(110,40),.5f,0f,0f,true);
                var hold=(HoldToStraighten)typeof(NoteCanvas).GetField("_straighten",BindingFlags.Instance|BindingFlags.NonPublic)!.GetValue(canvas)!;
                canvas.ResolveShapeSettings=()=>new ToolPreset("shape",DrawingToolKind.Shape) { StraightenDelayMillis=600 };
                hold.Begin(new(10,10),Environment.TickCount64-1000); hold.Move(new(110,40),Environment.TickCount64-800,2.5);
                Call("TryStraighten"); Call("EndPointer",true);
                Assert.Equal(2,canvas.Document.Strokes[^1].Points.Count); Assert.Equal("shape-v1",canvas.Document.Strokes[^1].BrushProfile);
            } catch (Exception e) { failure=e; }
        });
        if (failure!=null) throw failure;
    }
}
