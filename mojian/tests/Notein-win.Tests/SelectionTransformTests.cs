using System.Reflection;
using System.Windows;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win.Tests;

public class SelectionTransformTests
{
    [Fact]
    public void RealCanvasTinyImageMovesAndCornerResizeKeepsRatioWithoutPointerJump()
    {
        Exception? failure = null;
        WpfTestExecutor.Run(() =>
        {
            try
            {
                var image = new PageObject { Kind = PageObjectKind.Image, X = 100, Y = 100, Width = 8, Height = 4 };
                var canvas = new NoteCanvas { Document = new PageDocument("test", "page") { Objects = [image] } };
                void Call(string name, params object[] args) => typeof(NoteCanvas).GetMethod(name, BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas, args);
                var viewport = (ViewportState)typeof(NoteCanvas).GetField("_viewport", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(canvas)!;
                viewport.SetScale(.5f, 0, 0);
                Call("SelectOnlyObject", image);
                Call("BeginDirectPointer", new Point(52, 51), false);
                Call("ContinueDirectPointer", new Point(82, 71));
                Call("EndDirectPointer", new Point(82, 71));
                Assert.Equal(160, image.X); Assert.Equal(140, image.Y);
                Assert.Equal(8, image.Width); Assert.Equal(4, image.Height);
                canvas.Undo();
                image = Assert.Single(canvas.Document.Objects);
                Assert.Equal(100, image.X); Assert.Equal(100, image.Y);
                Assert.Equal(8, image.Width); Assert.Equal(4, image.Height);

                image.Width = 80; image.Height = 40;
                viewport.SetScale(1, 0, 0);
                Call("SelectOnlyObject", image);
                // Down is inside the invisible handle target rather than at its exact center.
                Call("BeginDirectPointer", new Point(184, 144), false);
                Call("ContinueDirectPointer", new Point(234, 154));
                Assert.Equal(2, image.Width / image.Height, 5);
                Assert.Equal(100, image.X); Assert.Equal(100, image.Y);
                Assert.Equal(124, image.Width, 3); Assert.Equal(62, image.Height, 3);
                // Retracing the same pointer path returns to the original geometry.
                Call("ContinueDirectPointer", new Point(184, 144));
                Call("EndDirectPointer", new Point(184, 144));
                Assert.Equal(80, image.Width, 3); Assert.Equal(40, image.Height, 3);
                Call("BeginDirectPointer", new Point(180, 120), false);
                Call("ContinueDirectPointer", new Point(220, 170));
                Call("EndDirectPointer", new Point(220, 170));
                Assert.Equal(120, image.Width, 3); Assert.Equal(40, image.Height, 3);

                var root = System.IO.Path.Combine(System.IO.Path.GetTempPath(), ".agents", "mojian-selection-tests", Guid.NewGuid().ToString("N"));
                try
                {
                    var store = new WorkspaceStore(root);
                    store.SavePage(canvas.Document);
                    var restored = Assert.Single(store.LoadPage("test", "page").Objects);
                    Assert.Equal(image.X, restored.X); Assert.Equal(image.Y, restored.Y);
                    Assert.Equal(image.Width, restored.Width); Assert.Equal(image.Height, restored.Height);
                }
                finally { if (System.IO.Directory.Exists(root)) System.IO.Directory.Delete(root, true); }
            }
            catch (Exception ex) { failure = ex; }
        });
        Assert.Null(failure);
    }
}
