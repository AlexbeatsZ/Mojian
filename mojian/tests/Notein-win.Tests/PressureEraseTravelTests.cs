using System.Reflection;
using System.Threading;
using System.Windows;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;
using Xunit;

namespace Notein_win.Tests;

public sealed class PressureEraseTravelTests
{
    [Fact]
    public void CountsRoundTripTravelAndLatchesUntilReset()
    {
        var guard = new PressureEraseTravelGuard();
        guard.Begin(0, 0, 1); guard.Observe(.6, 0); guard.Observe(0, 0);
        Assert.True(guard.Exceeded); Assert.Equal(1.2, guard.DistanceMm, 6);
        guard.Observe(0, 0); Assert.True(guard.Exceeded);
        guard.Reset(); guard.Begin(0, 0, 1); guard.Observe(1, 0);
        Assert.False(guard.Exceeded); guard.Observe(1.01, 0); Assert.True(guard.Exceeded);
    }

    [Fact]
    public void EraserOwnedDistanceDefaultsClampsAndRoundTrips()
    {
        var json = new System.Text.Json.Nodes.JsonObject { ["id"]="eraser-stroke", ["kind"]="Eraser" };
        Assert.Equal(1f, ToolPreset.FromJson(json)!.PressureEraseTravelLimitMm);
        json["pressureEraseTravelLimitMm"] = .001f;
        Assert.Equal(.1f, ToolPreset.FromJson(json)!.PressureEraseTravelLimitMm);
        json["pressureEraseTravelLimitMm"] = 50f;
        Assert.Equal(10f, ToolPreset.FromJson(json)!.PressureEraseTravelLimitMm);
        var shelf = new ToolShelf(); shelf.Tools.Single(t => t.Id == "eraser-stroke").PressureEraseTravelLimitMm = 2.3f;
        Assert.Equal(2.3f, ToolShelf.FromJson(shelf.ToJson())!.Tools.Single(t => t.Id == "eraser-stroke").PressureEraseTravelLimitMm);
    }

    [Fact]
    public void RealCanvasLongWritingCannotTurnIntoEraserAndLiftRearmsIt()
    {
        Exception? failure = null;
        WpfTestExecutor.Run(() => {
            try {
                var canvas = new NoteCanvas { Document=new PageDocument("n","p"), SelectedTool=new ToolPreset("pen",DrawingToolKind.Pen) };
                canvas.ResolvePressureEraserTool = () => new ToolPreset("eraser",DrawingToolKind.Eraser) { PressureEraseEnabled=true, PressureEraseTravelLimitMm=1f };
                void Call(string name, params object[] args) => typeof(NoteCanvas).GetMethod(name,BindingFlags.Instance|BindingFlags.NonPublic)!.Invoke(canvas,args);
                void Begin(Point p, float pressure) => Call("BeginPointer",p,pressure,0f,0f,true,false,false);
                void Move(Point p, float pressure) => Call("ContinuePointer",p,pressure,0f,0f,true);
                void Elapse() => typeof(NoteCanvas).GetField("_pressureStartedAt",BindingFlags.Instance|BindingFlags.NonPublic)!.SetValue(canvas,DateTime.UtcNow.AddSeconds(-1));
                bool State(string field) => (bool)typeof(NoteCanvas).GetField(field,BindingFlags.Instance|BindingFlags.NonPublic)!.GetValue(canvas)!;
                Begin(new(10,10),.2f); Move(new(30,10),.2f); Call("EndPointer",true);
                Begin(new(200,200),.2f); Move(new(202.5,200),.2f); Move(new(200,200),.95f);
                Assert.False(State("_pressureCandidate"));
                Elapse(); Call("CheckPressureEraseActivation"); Assert.False(State("_temporaryPressureEraser"));
                Assert.Equal(2,canvas.Document.Strokes.Count); Call("EndPointer",true);
                Begin(new(200,200),.95f); Assert.True(State("_pressureCandidate"));
                Elapse(); Call("CheckPressureEraseActivation"); Assert.True(State("_temporaryPressureEraser"));
                Assert.Single(canvas.Document.Strokes);
                Move(new(250,250),.95f); Assert.True(State("_temporaryPressureEraser"));
                Call("EndPointer",true); Assert.False(State("_temporaryPressureEraser"));
            } catch(Exception ex) { failure=ex; }
        });
        if(failure!=null) throw failure;
    }
}
