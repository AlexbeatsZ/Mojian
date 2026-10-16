using System.Reflection;
using System.Threading;
using System.Windows;
using Notein_win.Controls;
using Notein_win.Models;

namespace Notein_win.Tests;

public sealed class InsertSpaceTests
{
    private static void RunSta(Action action)
    {
        Exception? failure = null;
        var thread = new Thread(() => { try { action(); } catch (Exception e) { failure = e; } });
        thread.SetApartmentState(ApartmentState.STA); thread.Start(); thread.Join();
        if (failure != null) throw failure;
    }

    private static StrokeRecord Line(string id, float y) =>
        new(id, DrawingToolKind.Pen, InkPalette.Black, .4f, points: [new InkPoint(20, y, .5f), new InkPoint(120, y + 4, .5f)]);

    [Fact]
    public void PenDragShiftsContentBelowTheStartLineAndUndoRestoresIt()
    {
        RunSta(() =>
        {
            var document = new PageDocument("n", "p");
            document.Strokes.Add(Line("above", 40));
            document.Strokes.Add(Line("below", 200));
            document.TextItems.Add(new TextItem { X = 30, Y = 220, Text = "t" });
            var canvas = new NoteCanvas { Document = document, SelectedTool = new ToolPreset("pen", DrawingToolKind.Pen), InsertSpaceModeEnabled = true };
            void Call(string name, params object[] args) => typeof(NoteCanvas).GetMethod(name, BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas, args);
            Call("BeginPointer", new Point(60, 100), .5f, 0f, 0f, true, false, false);
            Call("ContinuePointer", new Point(60, 130), .5f, 0f, 0f, true);
            Call("ContinuePointer", new Point(60, 180), .5f, 0f, 0f, true);
            Call("EndPointer", true);
            Assert.Equal(40, document.Strokes[0].Points[0].Y);
            Assert.Equal(280, document.Strokes[1].Points[0].Y, 3);
            Assert.Equal(300, document.TextItems[0].Y, 3);
            Assert.Equal(2, document.Strokes.Count);
            canvas.Undo();
            Assert.Equal(200, canvas.Document!.Strokes[1].Points[0].Y, 3);
        });
    }

    [Fact]
    public void MouseDragInsertsSpaceWhileStationaryClickOnlySelects()
    {
        RunSta(() =>
        {
            var document = new PageDocument("n", "p");
            document.Strokes.Add(Line("above", 40));
            document.Strokes.Add(Line("below", 200));
            var canvas = new NoteCanvas { Document = document, SelectedTool = new ToolPreset("pen", DrawingToolKind.Pen), InsertSpaceModeEnabled = true };
            void Call(string name, params object[] args) => typeof(NoteCanvas).GetMethod(name, BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas, args);
            Call("BeginMouseInsertSpace", new Point(60, 100));
            Call("ContinueMouseInsertSpace", new Point(60, 101));
            Call("EndMouseInsertSpace");
            Assert.Equal(200, document.Strokes[1].Points[0].Y, 3);
            Assert.Empty((System.Collections.ICollection)typeof(NoteCanvas).GetField("_undo", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(canvas)!);

            Call("BeginMouseInsertSpace", new Point(60, 100));
            Call("ContinueMouseInsertSpace", new Point(60, 140));
            Call("ContinueMouseInsertSpace", new Point(60, 160));
            Call("EndMouseInsertSpace");
            Assert.Equal(40, document.Strokes[0].Points[0].Y, 3);
            Assert.Equal(260, document.Strokes[1].Points[0].Y, 3);
            canvas.Undo();
            Assert.Equal(200, canvas.Document!.Strokes[1].Points[0].Y, 3);
        });
    }
}
