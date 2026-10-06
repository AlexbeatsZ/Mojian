using System.Globalization;
using System.Windows.Data;
using Notein_win.Services;

namespace Notein_win.ViewModels;

public sealed class ShapeLabelConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture) =>
        value is ShapeKind kind ? ShapeGeometry.Label(kind) : string.Empty;
    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => Binding.DoNothing;
}
