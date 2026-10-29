using System.Reflection;
using System.IO;
using System.Text.Json;
using System.Threading;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win.Tests;

public class ImportedVectorTests
{
    private static void Sta(Action action)
    {
        Exception? failure = null;
        WpfTestExecutor.Run(() => { try { action(); } catch (Exception ex) { failure = ex; } });
        Assert.Null(failure);
    }

    private static StrokeRecord Vector() => new()
    {
        Id = "source-stroke", ColorHex = "#FFE02020", LayerId = "ink", ZOrder = 10,
        VectorPath = "M 20,20 L 120,20 120,30 20,30 Z",
        Points = [new InkPoint(20,25), new InkPoint(120,25)],
    };

    [Fact]
    public void VectorMoveResizeRoundTripRetainsOutlineColorAndSourceLayer() => Sta(() =>
    {
        var stroke = Vector();
        stroke.TransformVector(new Matrix(1,0,0,1,50,60));
        Assert.Equal(new Rect(70,80,100,10), stroke.Bounds);
        stroke.TransformVector(new Matrix(2,0,0,3,-70,-160));
        Assert.Equal(new Rect(70,80,200,30), stroke.Bounds);
        var restored = JsonSerializer.Deserialize<StrokeRecord>(JsonSerializer.Serialize(stroke))!;
        Assert.Equal(stroke.Bounds, restored.Bounds);
        Assert.Equal("#FFE02020", restored.ColorHex);
        Assert.Equal("ink", restored.LayerId);
        Assert.Equal(stroke.VectorPath, restored.VectorPath);
    });

    [Fact]
    public void SourceLayersInterleaveImagesAndInkAndKeepHiddenContent()
    {
        var doc = new PageDocument("n","p")
        {
            Layers = [new PageLayer { Id="lower", Order=0 }, new PageLayer { Id="upper", Order=1 }, new PageLayer { Id="hidden", Order=2, Visible=false }],
            Objects = [new PageObject { Id="image-above-ink", LayerId="upper" }],
            Strokes = [new StrokeRecord { Id="ink-under-image", LayerId="lower" }, new StrokeRecord { Id="hidden-ink", LayerId="hidden" }],
        };
        Assert.Equal(new object[] {doc.Strokes[0],doc.Objects[0]}, PageScene.OrderedContent(doc));
        Assert.Equal(2, doc.Strokes.Count);
    }

    [Fact]
    public void RealCanvasPreservesLayerOcclusionAndHitsTheVisibleTopItem() => Sta(() =>
    {
        _ = Application.Current ?? new Application();
        var ink=Vector();
        var cover=new PageObject { Id="cover",LayerId="cover-layer",Kind=PageObjectKind.Table,X=10,Y=10,Width=130,Height=40 };
        var doc=new PageDocument("n","p") { Strokes=[ink],Objects=[cover],Layers=[new PageLayer {Id="ink",Order=0},new PageLayer {Id="cover-layer",Order=1}] };
        var canvas=new NoteCanvas { Document=doc,Appearance=new PageAppearance {ShowTitle=false,Theme=PageTheme.Light,Background=CanvasBackgroundFormat.Blank} };
        object? Hit() => typeof(NoteCanvas).GetMethod("HitSourceContent",BindingFlags.Instance|BindingFlags.NonPublic)!.Invoke(canvas,[new Point(70,25)]);
        bool HasRed()
        {
            var bitmap=canvas.RenderLayerBitmap(CanvasRenderLayer.Composite);
            var pixels=new byte[bitmap.PixelWidth*bitmap.PixelHeight*4];bitmap.CopyPixels(pixels,bitmap.PixelWidth*4,0);
            return Enumerable.Range(0,pixels.Length/4).Any(i=>pixels[i*4+2]>200 && pixels[i*4+1]<80 && pixels[i*4]<80);
        }
        Assert.Same(cover,Hit());Assert.False(HasRed());
        doc.Layers[1].Visible=false;
        Assert.Same(ink,Hit());Assert.True(HasRed());
        Assert.Single(doc.Objects);
    });

    [Fact]
    public void RealCanvasMovesAndErasesOnlyTheSelectedVectorAndKeepsImages() => Sta(() =>
    {
        _ = Application.Current ?? new Application();
        var doc = new PageDocument("n","p") { Strokes=[Vector()], Layers=[new PageLayer { Id="ink" }] };
        doc.Objects.Add(new PageObject { Id="image", Kind=PageObjectKind.Image, X=250, Y=200, Width=40, Height=40 });
        var canvas = new NoteCanvas { Document=doc, Appearance=new PageAppearance { ShowTitle=false } };
        void Invoke(string method, params object[] args) => typeof(NoteCanvas).GetMethod(method, BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas,args);
        Invoke("SelectOnlyStroke",doc.Strokes[0]);
        Invoke("TranslateSelection",50d,60d);
        Assert.Equal(new Rect(70,80,100,10),doc.Strokes[0].Bounds);
        Assert.Equal(250,doc.Objects[0].X);
        Invoke("PerformErase",new Point(100,85),2d);
        Assert.Empty(doc.Strokes);
        Assert.Single(doc.Objects);
    });

    [Fact]
    public void RealRendererUsesSourceColorAndSourceStackingInsteadOfAuthoringBrush() => Sta(() =>
    {
        _ = Application.Current ?? new Application();
        var doc = new PageDocument("n","p") { Strokes=[Vector()], Layers=[new PageLayer { Id="ink" }] };
        var canvas = new NoteCanvas { Document=doc, Appearance=new PageAppearance { ShowTitle=false, Theme=PageTheme.Light, Background=CanvasBackgroundFormat.Blank } };
        var bitmap=canvas.RenderLayerBitmap(CanvasRenderLayer.Composite);
        var pixels=new byte[bitmap.PixelWidth*bitmap.PixelHeight*4];
        bitmap.CopyPixels(pixels,bitmap.PixelWidth*4,0);
        Assert.Contains(Enumerable.Range(0,pixels.Length/4),i => pixels[i*4+2]>200 && pixels[i*4+1]<80 && pixels[i*4]<80);
    });

    [Fact]
    public void RealCanvasRendersThinEditableVectorAtSixtyFourTimesZoom() => Sta(() =>
    {
        _ = Application.Current ?? new Application();
        var stroke = Vector();
        stroke.VectorPath = "M 20,20 L 120,20 120,20.1 20,20.1 Z";
        var sourcePath = stroke.VectorPath;
        var document = new PageDocument("n", "p") { Strokes = [stroke], Layers = [new PageLayer { Id = "ink" }] };
        var canvas = new NoteCanvas
        {
            Width = 400, Height = 300, Document = document,
            Appearance = new PageAppearance { ShowTitle = false, Theme = PageTheme.Light, Background = CanvasBackgroundFormat.Blank },
        };
        canvas.Measure(new Size(400, 300));
        canvas.Arrange(new Rect(0, 0, 400, 300));
        canvas.SetZoom(64f);
        canvas.CenterOnDocumentPoint(70, 20.05);
        canvas.UpdateLayout();
        var bitmap = new RenderTargetBitmap(400, 300, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(canvas);
        var pixels = new byte[400 * 300 * 4];
        bitmap.CopyPixels(pixels, 400 * 4, 0);
        bool IsRed(int x, int y)
        {
            var index = (y * 400 + x) * 4;
            return pixels[index + 2] > 200 && pixels[index + 1] < 80 && pixels[index] < 80;
        }
        Assert.True(IsRed(200, 150));
        Assert.False(IsRed(200, 140));
        Assert.False(IsRed(200, 160));
        Assert.Equal(64f, document.Viewport.Scale);
        Assert.Equal(sourcePath, stroke.VectorPath);
        Assert.Single(document.Strokes);
    });

    [Fact]
    public void SuppliedMigrationPageRendersVectorsAtEightTimesZoomAndMovesWithoutFlattening() => Sta(() =>
    {
        var path=Environment.GetEnvironmentVariable("MOJIAN_VECTOR_PAGE_JSON");
        if (string.IsNullOrEmpty(path)) return;
        _ = Application.Current ?? new Application();
        var document=JsonSerializer.Deserialize<PageDocument>(File.ReadAllText(path),new JsonSerializerOptions { PropertyNameCaseInsensitive=true })!;
        Assert.NotEmpty(document.Strokes);
        Assert.All(document.Strokes,stroke=>Assert.NotEmpty(stroke.VectorPath));
        var target=document.Strokes.First(stroke=>stroke.Points.Count>2 && stroke.ColorHex != "#FFFFFFFF" && PageScene.IsVisible(document,stroke.LayerId));
        var canvas=new NoteCanvas { Width=1100,Height=700,Document=document,Appearance=new PageAppearance { ShowTitle=false,Theme=PageTheme.Light,PageColor="#FFFFFF",Background=CanvasBackgroundFormat.Blank } };
        var assetRoot=Path.Combine(Path.GetDirectoryName(Path.GetDirectoryName(path))!,"assets",document.PageId);
        canvas.ResolveAssetPath=name=>Path.Combine(assetRoot,name);
        using var diagnostics=new StringWriter();
        using var listener=new System.Diagnostics.TextWriterTraceListener(diagnostics);
        System.Diagnostics.Trace.Listeners.Add(listener);
        var sourcePdf=document.Objects.FirstOrDefault(item=>!string.IsNullOrEmpty(item.SourceAssetName));
        if (sourcePdf != null)
        {
            var render=PdfImportService.RenderPageAsync(Path.Combine(assetRoot,sourcePdf.SourceAssetName),sourcePdf.SourcePageNumber??1,2048);
            while (!render.IsCompleted)
            {
                var frame=new System.Windows.Threading.DispatcherFrame();
                var timer=new System.Windows.Threading.DispatcherTimer { Interval=TimeSpan.FromMilliseconds(20) };
                timer.Tick+=(_,_)=> {timer.Stop();frame.Continue=false;};
                timer.Start();System.Windows.Threading.Dispatcher.PushFrame(frame);
            }
            Assert.NotEmpty(render.GetAwaiter().GetResult().PngBytes);
        }
        Assert.True(target.VectorGeometry!.Bounds.IntersectsWith(target.Bounds));
        var x=target.VectorGeometry.Bounds.Left; var y=target.VectorGeometry.Bounds.Top;
        var directory=Path.Combine(Path.GetTempPath(),".agents","mojian-vector-import","acceptance");
        Directory.CreateDirectory(directory);
        foreach (var zoom in new[]{1f,8f})
        {
            canvas.Viewport.Restore(new PageViewport { Scale=zoom,TranslationX=50-(float)x*zoom,TranslationY=50-(float)y*zoom });
            canvas.InvalidateVisual();
            canvas.Measure(new Size(1100,700)); canvas.Arrange(new Rect(0,0,1100,700)); canvas.UpdateLayout();
            System.Windows.Threading.Dispatcher.CurrentDispatcher.Invoke(()=>{},System.Windows.Threading.DispatcherPriority.Render);
            // Pump the actual dispatcher while the canvas rerenders visible
            // printouts from their original PDF instead of capturing a placeholder.
            var pending=(HashSet<string>)typeof(NoteCanvas).GetField("_pendingPdfRenders",BindingFlags.Instance|BindingFlags.NonPublic)!.GetValue(canvas)!;
            var deadline=DateTime.UtcNow.AddSeconds(30);
            while (pending.Count>0 && DateTime.UtcNow<deadline)
            {
                var frame=new System.Windows.Threading.DispatcherFrame();
                var timer=new System.Windows.Threading.DispatcherTimer(System.Windows.Threading.DispatcherPriority.Background) { Interval=TimeSpan.FromMilliseconds(20) };
                timer.Tick+=(_,_)=> { timer.Stop();frame.Continue=false; };
                timer.Start();System.Windows.Threading.Dispatcher.PushFrame(frame);
            }
            Assert.Empty(pending);
            var failed=(HashSet<string>)typeof(NoteCanvas).GetField("_failedPdfRenders",BindingFlags.Instance|BindingFlags.NonPublic)!.GetValue(canvas)!;
            Assert.True(failed.Count==0,diagnostics.ToString());
            canvas.UpdateLayout();
            var bitmap=new RenderTargetBitmap(1100,700,96,96,PixelFormats.Pbgra32); bitmap.Render(canvas);
            var encoder=new PngBitmapEncoder();encoder.Frames.Add(BitmapFrame.Create(bitmap));
            using var stream=File.Create(Path.Combine(directory,$"{document.PageId}-{zoom}x.png"));encoder.Save(stream);
            var pixels=new byte[1100*700*4];bitmap.CopyPixels(pixels,1100*4,0);
            Assert.Contains(Enumerable.Range(0,pixels.Length/4),i=>pixels[i*4]<100 && pixels[i*4+1]<100 && pixels[i*4+2]<100 && pixels[i*4+3]>0);
        }
        var original=target.Bounds;var objectJson=JsonSerializer.Serialize(document.Objects);
        typeof(NoteCanvas).GetMethod("SelectOnlyStroke",BindingFlags.Instance|BindingFlags.NonPublic)!.Invoke(canvas,[target]);
        typeof(NoteCanvas).GetMethod("TranslateSelection",BindingFlags.Instance|BindingFlags.NonPublic)!.Invoke(canvas,[80d,60d]);
        Assert.Equal(original.Left+80,target.Bounds.Left,3);Assert.Equal(original.Top+60,target.Bounds.Top,3);
        Assert.Equal(objectJson,JsonSerializer.Serialize(document.Objects));
        System.Diagnostics.Trace.Listeners.Remove(listener);
        Assert.True(canvas.DeleteSelection());Assert.DoesNotContain(target,document.Strokes);
        Assert.Equal(objectJson,JsonSerializer.Serialize(document.Objects));
    });
}
