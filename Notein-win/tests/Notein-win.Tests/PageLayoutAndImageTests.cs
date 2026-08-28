using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win.Tests;

public class PageLayoutAndImageTests
{
    [Fact]
    public void A4GrowsForContentOrVisibleViewportAndNeverShrinks()
    {
        const double height = 1122.5;
        var firstThreshold = height - A4PageLayout.AutoGrowMargin;

        Assert.Equal(1, A4PageLayout.RequiredSheetCount(firstThreshold - 1, 0, 1, height));
        Assert.Equal(2, A4PageLayout.RequiredSheetCount(firstThreshold, 0, 1, height));
        Assert.Equal(2, A4PageLayout.RequiredSheetCount(0, firstThreshold, 1, height));
        Assert.Equal(4, A4PageLayout.RequiredSheetCount(0, 0, 4, height));
    }

    [Theory]
    [InlineData(1920, 1080, 620, 348.75)]
    [InlineData(200, 400, 200, 400)]
    public void ClipboardImagesPreserveAspectRatioWithinInsertionWidth(int width, int height, double expectedWidth, double expectedHeight)
    {
        var fitted = ImagePlacement.FitPixels(width, height);

        Assert.Equal(expectedWidth, fitted.Width, 3);
        Assert.Equal(expectedHeight, fitted.Height, 3);
    }
}
